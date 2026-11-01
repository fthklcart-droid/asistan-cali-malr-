"""Görev listesi ve zamanlı hatırlatmalar (yerel SQLite)."""
from __future__ import annotations

from datetime import datetime, timedelta

from .. import db

DURUMLAR = {"bekliyor", "devam", "duraklatildi", "bitti"}


def zaman_coz(ifade: str, simdi: datetime | None = None) -> datetime:
    """'2026-11-02 09:30', '09:30', '+15dk', '+2saat', '+1gün' biçimlerini çözer."""
    now = simdi or datetime.now()
    s = ifade.strip().lower().replace("ı", "i").replace("ü", "u")
    if s.startswith("+"):
        import re
        m = re.fullmatch(r"\+\s*(\d+)\s*(dk|dakika|m|sa|saat|h|g|gun)", s)
        if not m:
            raise ValueError("Göreli zaman biçimi: +15dk, +2saat, +1gün")
        n, unit = int(m.group(1)), m.group(2)
        delta = {"dk": timedelta(minutes=n), "dakika": timedelta(minutes=n), "m": timedelta(minutes=n),
                 "sa": timedelta(hours=n), "saat": timedelta(hours=n), "h": timedelta(hours=n),
                 "g": timedelta(days=n), "gun": timedelta(days=n)}[unit]
        return now + delta
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%d.%m.%Y %H:%M"):
        try:
            return datetime.strptime(ifade.strip(), fmt)
        except ValueError:
            pass
    for fmt in ("%H:%M", "%H.%M"):
        try:
            t = datetime.strptime(ifade.strip(), fmt)
            cand = now.replace(hour=t.hour, minute=t.minute, second=0, microsecond=0)
            return cand if cand > now else cand + timedelta(days=1)
        except ValueError:
            pass
    raise ValueError("Zaman anlaşılamadı. Örnek: '2026-11-02 09:30', '18:45' veya '+20dk'")


# ---------- görevler ----------
def gorev_ekle(metin: str, tarih: str = "") -> dict:
    if not metin.strip():
        raise ValueError("Görev metni boş.")
    if tarih:
        datetime.strptime(tarih, "%Y-%m-%d")
    with db.session() as conn:
        cur = conn.execute("INSERT INTO todos (metin, tarih, olusturma) VALUES (?, ?, ?)",
                           (metin.strip(), tarih, datetime.now().isoformat(timespec="seconds")))
    return {"no": cur.lastrowid, "metin": metin.strip(), "tarih": tarih}


def gorev_listele(hepsi: bool = False) -> dict:
    with db.session() as conn:
        q = "SELECT id, metin, durum, tarih FROM todos" + ("" if hepsi else " WHERE durum != 'bitti'") + " ORDER BY id"
        rows = conn.execute(q).fetchall()
    return {"gorevler": [{"no": r[0], "metin": r[1], "durum": r[2], "tarih": r[3]} for r in rows]}


def gorev_durum(no: int, durum: str) -> dict:
    if durum not in DURUMLAR:
        raise ValueError("Durum: " + ", ".join(sorted(DURUMLAR)))
    with db.session() as conn:
        cur = conn.execute("UPDATE todos SET durum=? WHERE id=?", (durum, int(no)))
    if not cur.rowcount:
        raise ValueError(f"{no} numaralı görev yok.")
    return {"no": no, "durum": durum}


def gorev_sil(no: int) -> dict:
    with db.session() as conn:
        cur = conn.execute("DELETE FROM todos WHERE id=?", (int(no),))
    return {"silindi": cur.rowcount > 0, "no": no}


# ---------- hatırlatmalar ----------
def hatirlatma_kur(metin: str, zaman: str) -> dict:
    when = zaman_coz(zaman)
    if when < datetime.now() - timedelta(minutes=1):
        raise ValueError("Geçmiş bir zamana hatırlatma kurulamaz.")
    with db.session() as conn:
        cur = conn.execute("INSERT INTO reminders (metin, zaman) VALUES (?, ?)",
                           (metin.strip(), when.isoformat(timespec="minutes")))
    return {"id": cur.lastrowid, "metin": metin.strip(), "zaman": when.strftime("%d.%m.%Y %H:%M")}


def hatirlatma_listele() -> dict:
    with db.session() as conn:
        rows = conn.execute("SELECT id, metin, zaman FROM reminders WHERE gosterildi=0 ORDER BY zaman").fetchall()
    return {"hatirlatmalar": [{"id": r[0], "metin": r[1], "zaman": r[2].replace("T", " ")} for r in rows]}


def hatirlatma_sil(kimlik: int) -> dict:
    with db.session() as conn:
        cur = conn.execute("DELETE FROM reminders WHERE id=?", (int(kimlik),))
    return {"silindi": cur.rowcount > 0, "id": kimlik}


def zamani_gelenler(simdi: datetime | None = None) -> list[dict]:
    """Zamanı gelmiş ve henüz gösterilmemiş hatırlatmaları döndürür ve gösterildi işaretler."""
    now = (simdi or datetime.now()).isoformat(timespec="minutes")
    with db.session() as conn:
        rows = conn.execute("SELECT id, metin, zaman FROM reminders WHERE gosterildi=0 AND zaman <= ?", (now,)).fetchall()
        if rows:
            conn.executemany("UPDATE reminders SET gosterildi=1 WHERE id=?", [(r[0],) for r in rows])
    return [{"id": r[0], "metin": r[1], "zaman": r[2]} for r in rows]


def gunun_ozeti() -> dict:
    today = datetime.now().strftime("%Y-%m-%d")
    gorevler = [g for g in gorev_listele()["gorevler"] if not g["tarih"] or g["tarih"] <= today]
    hat = [h for h in hatirlatma_listele()["hatirlatmalar"] if h["zaman"][:10] == today]
    return {"tarih": today, "acik_gorevler": gorevler, "bugunku_hatirlatmalar": hat}
