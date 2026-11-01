"""Görev servisi: görev yaşam döngüsü, durum makinesi ve zaman çizelgesi.

    queued ─► planning ─► planned ─► running ─► done
       │          │          │   └─► awaiting_approval ─► running / done / failed
       └──────────┴──────────┴──► cancelled        (failed → queued ile yeniden denenebilir)

Mimari belgedeki yaşam döngüsü: görev alınır → sınıflandırılır → Claude planlar → eylemler politika
motorundan ve onaydan geçer → yürütülür → sonuç raporlanır. Her adım `task_events` tablosuna yazılır.
"""
from __future__ import annotations

import json
import re
from uuid import uuid4

from . import audit, db

STATUSES = ("queued", "planning", "planned", "awaiting_approval", "running", "done", "failed", "cancelled")
ACTIVE = {"queued", "planning", "planned", "awaiting_approval", "running"}
TRANSITIONS: dict[str, set[str]] = {
    "queued": {"planning", "running", "awaiting_approval", "cancelled", "failed"},
    "planning": {"planned", "failed", "cancelled"},
    "planned": {"planning", "running", "awaiting_approval", "done", "cancelled"},
    "awaiting_approval": {"running", "done", "failed", "cancelled", "awaiting_approval"},
    "running": {"done", "failed", "cancelled", "awaiting_approval"},
    "failed": {"queued", "planning"},
    "done": set(),
    "cancelled": set(),
}
STATUS_TR = {"queued": "Sırada", "planning": "Planlanıyor", "planned": "Planlandı",
             "awaiting_approval": "Onay bekliyor", "running": "Çalışıyor", "done": "Tamamlandı",
             "failed": "Başarısız", "cancelled": "İptal edildi"}

# Mimari belgedeki görev türleri: tasarım, video, web, iletişim, dosya
CATEGORIES: dict[str, tuple[str, ...]] = {
    "tasarim": ("photoshop", "lightroom", "görsel", "gorsel", "fotoğraf", "fotograf", "tasarım", "tasarim", "logo",
                "afiş", "afis", "banner", "katalog", "renk", "retouch", "rötuş"),
    "video": ("premiere", "capcut", "video", "montaj", "kurgu", "reels", "shorts", "klip", "altyazı"),
    "iletisim": ("whatsapp", "mesaj", "e-posta", "eposta", "mail", "müşteri", "musteri", "telefon", "ara ", "sms"),
    "web": ("internet", "web", "site", "google", "tarayıcı", "tarayici", "chrome", "araştır", "arastir", "haber",
            "youtube", "instagram"),
    "dosya": ("dosya", "klasör", "klasor", "pdf", "excel", "word", "belge", "fatura", "csv", "yedek"),
}
CATEGORY_TR = {"tasarim": "Tasarım", "video": "Video", "iletisim": "İletişim", "web": "Web", "dosya": "Dosya",
               "genel": "Genel"}


class TaskError(ValueError):
    pass


def classify(text: str) -> str:
    t = " " + text.replace("İ", "i").replace("I", "ı").lower() + " "
    scores = {cat: sum(1 for w in words if w in t) for cat, words in CATEGORIES.items()}
    best = max(scores, key=scores.get)
    return best if scores[best] else "genel"


def _title(instruction: str) -> str:
    one = re.sub(r"\s+", " ", instruction).strip()
    return one if len(one) <= 80 else one[:77].rstrip() + "…"


def _row(r) -> dict:
    d = dict(r)
    d["status_tr"] = STATUS_TR.get(d["status"], d["status"])
    d["category_tr"] = CATEGORY_TR.get(d["category"], d["category"])
    return d


def add_event(task_id: str, type_: str, message: str = "", data: dict | None = None, conn=None) -> None:
    payload = json.dumps(data, ensure_ascii=False) if data else ""
    sql = "INSERT INTO task_events (task_id, ts, type, message, data) VALUES (?, ?, ?, ?, ?)"
    if conn is not None:
        conn.execute(sql, (task_id, db.now(), type_, message[:4000], payload))
        return
    with db.session() as c:
        c.execute(sql, (task_id, db.now(), type_, message[:4000], payload))


def create(instruction: str, source: str = "ui", title: str = "", category: str = "") -> dict:
    instruction = instruction.strip()
    if len(instruction) < 3:
        raise TaskError("Görev en az 3 karakter olmalı.")
    task_id = str(uuid4())
    cat = category if category in CATEGORY_TR else classify(instruction)
    ts = db.now()
    with db.session() as conn:
        conn.execute("INSERT INTO tasks (id, title, instruction, category, status, source, created_at, updated_at) "
                     "VALUES (?, ?, ?, ?, 'queued', ?, ?, ?)",
                     (task_id, title or _title(instruction), instruction, cat, source, ts, ts))
        add_event(task_id, "created", f"Görev oluşturuldu ({CATEGORY_TR[cat]}, kaynak: {source})", conn=conn)
    audit.record("gorev_olusturuldu", task_id, {"category": cat, "source": source})
    return get(task_id)


def get(task_id: str, with_events: bool = False) -> dict | None:
    with db.session(write=False) as conn:
        r = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not r:
            return None
        task = _row(r)
        if with_events:
            task["events"] = [dict(e) for e in conn.execute(
                "SELECT id, ts, type, message, data FROM task_events WHERE task_id = ? ORDER BY id", (task_id,))]
    return task


def list_tasks(status: str | None = None, limit: int = 100, active_only: bool = False) -> list[dict]:
    sql, args = "SELECT * FROM tasks", []
    if status:
        sql += " WHERE status = ?"; args.append(status)
    elif active_only:
        sql += f" WHERE status IN ({','.join('?' * len(ACTIVE))})"; args += sorted(ACTIVE)
    sql += " ORDER BY updated_at DESC LIMIT ?"; args.append(max(1, min(limit, 1000)))
    with db.session(write=False) as conn:
        return [_row(r) for r in conn.execute(sql, args)]


def transition(task_id: str, new_status: str, message: str = "", **fields) -> dict:
    if new_status not in STATUSES:
        raise TaskError(f"Geçersiz durum: {new_status}")
    allowed_fields = {"plan", "result", "error"}
    with db.session() as conn:
        r = conn.execute("SELECT status FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not r:
            raise TaskError("Görev bulunamadı.")
        old = r[0]
        if new_status != old and new_status not in TRANSITIONS[old]:
            raise TaskError(f"'{STATUS_TR[old]}' durumundaki görev '{STATUS_TR[new_status]}' yapılamaz.")
        sets = ["status = ?", "updated_at = ?"]
        args: list = [new_status, db.now()]
        for k, v in fields.items():
            if k in allowed_fields and v is not None:
                sets.append(f"{k} = ?"); args.append(str(v)[:50000])
        args.append(task_id)
        conn.execute(f"UPDATE tasks SET {', '.join(sets)} WHERE id = ?", args)
        add_event(task_id, "status", message or f"{STATUS_TR[old]} → {STATUS_TR[new_status]}",
                  {"from": old, "to": new_status}, conn=conn)
    return get(task_id)


def cancel(task_id: str, reason: str = "Kullanıcı iptal etti") -> dict:
    task = get(task_id)
    if not task:
        raise TaskError("Görev bulunamadı.")
    if task["status"] not in ACTIVE:
        raise TaskError("Bu görev zaten bitmiş.")
    with db.session() as conn:
        conn.execute("UPDATE approvals SET status = 'expired', decided_at = ?, error = ? "
                     "WHERE task_id = ? AND status = 'pending'", (db.now(), reason, task_id))
    audit.record("gorev_iptal", task_id, {"reason": reason})
    return transition(task_id, "cancelled", reason)


def cancel_active(reason: str) -> int:
    n = 0
    for t in list_tasks(active_only=True, limit=1000):
        try:
            transition(t["id"], "cancelled", reason)
            n += 1
        except TaskError:
            pass
    return n


def counts() -> dict:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT status, COUNT(*) FROM tasks GROUP BY status").fetchall()
    out = {s: 0 for s in STATUSES}
    out.update({r[0]: r[1] for r in rows})
    out["active"] = sum(out[s] for s in ACTIVE)
    return out


def recover_interrupted(reason: str = "Uygulama kapanırken görev yarıda kaldı") -> int:
    """Açılışta çağrılır: önceki oturumda 'running'/'planning' kalan görevleri 'failed' yapar.

    Bu görevlerin süreci artık yok (ajan iş parçacığı ve Claude isteği uygulamayla birlikte sonlandı);
    sonsuza dek 'çalışıyor' görünmeleri yanıltıcı olur. 'failed' görevler Görev Panosu'ndan yeniden planlanabilir.
    """
    n = 0
    for status in ("running", "planning"):
        for t in list_tasks(status=status, limit=1000):
            try:
                transition(t["id"], "failed", reason, error=reason)
                n += 1
            except TaskError:
                pass
    if n:
        audit.record("yarim_gorevler_kurtarildi", "sistem", {"count": n}, "sistem")
    return n
