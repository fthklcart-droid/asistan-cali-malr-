"""Kalıcı onay kuyruğu (data/jarvis.db › approvals).

Durumlar: pending → approved / rejected; approved → executed / failed.
Bekleyen onaylar uygulama yeniden başlasa da kaybolmaz.
"""
from __future__ import annotations

import json
from uuid import uuid4

from . import db

FINAL = {"rejected", "executed", "failed", "expired"}


def _row(r) -> dict:
    return {
        "id": r["id"], "task_id": r["task_id"], "action": json.loads(r["action"]), "summary": r["summary"],
        "risk": r["risk"], "status": r["status"], "created_at": r["created_at"], "decided_at": r["decided_at"],
        "result": r["result"], "error": r["error"],
    }


def create(action: dict, task_id: str | None = None, risk: str = "local", summary: str = "") -> dict:
    item_id = str(uuid4())
    with db.session() as conn:
        conn.execute(
            "INSERT INTO approvals (id, task_id, kind, action, summary, risk, status, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)",
            (item_id, task_id, str(action.get("action", "")), json.dumps(action, ensure_ascii=False),
             summary or str(action.get("summary", "")), risk, db.now()))
    return get(item_id)


def get(item_id: str) -> dict | None:
    with db.session(write=False) as conn:
        r = conn.execute("SELECT * FROM approvals WHERE id = ?", (item_id,)).fetchone()
    return _row(r) if r else None


def list_pending() -> list[dict]:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT * FROM approvals WHERE status = 'pending' ORDER BY created_at").fetchall()
    return [_row(r) for r in rows]


def list_recent(limit: int = 100) -> list[dict]:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT * FROM approvals ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
    return [_row(r) for r in rows]


def for_task(task_id: str) -> list[dict]:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT * FROM approvals WHERE task_id = ? ORDER BY created_at", (task_id,)).fetchall()
    return [_row(r) for r in rows]


def decide(item_id: str, approved: bool) -> dict | None:
    """Bekleyen onayı karara bağlar. Zaten karara bağlanmışsa None (çift onay/çift çalıştırma olmaz)."""
    with db.session() as conn:
        cur = conn.execute("UPDATE approvals SET status = ?, decided_at = ? WHERE id = ? AND status = 'pending'",
                           ("approved" if approved else "rejected", db.now(), item_id))
        if cur.rowcount != 1:
            return None
    return get(item_id)


def finish(item_id: str, result: str = "", error: str = "") -> dict | None:
    """Onaylanmış eylemin sonucunu yazar (executed / failed)."""
    with db.session() as conn:
        conn.execute("UPDATE approvals SET status = ?, result = ?, error = ? WHERE id = ? AND status = 'approved'",
                     ("failed" if error else "executed", result[:5000], error[:5000], item_id))
    return get(item_id)


def expire_all(reason: str = "acil durdurma") -> int:
    """Bekleyen tüm onayları geçersiz kılar (acil durdurmada kullanılır)."""
    with db.session() as conn:
        cur = conn.execute("UPDATE approvals SET status = 'expired', decided_at = ?, error = ? WHERE status = 'pending'",
                           (db.now(), reason))
        return cur.rowcount


def counts() -> dict:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT status, COUNT(*) FROM approvals GROUP BY status").fetchall()
    return {r[0]: r[1] for r in rows}
