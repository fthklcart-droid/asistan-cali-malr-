"""Kullanıcıya dönük etkinlik akışı (Geçmiş sayfası). Veriler data/jarvis.db içindeki `events` tablosunda."""
from __future__ import annotations

import json

from . import db


def log(kind: str, title: str, detail: str | dict = "", status: str = "") -> int:
    if not isinstance(detail, str):
        detail = json.dumps(detail, ensure_ascii=False)
    with db.session() as conn:
        cur = conn.execute("INSERT INTO events (ts, kind, title, detail, status) VALUES (?, ?, ?, ?, ?)",
                           (db.now(), kind, title[:500], detail[:20000], status))
        return int(cur.lastrowid)


def recent(limit: int = 200) -> list[dict]:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT id, ts, kind, title, detail, status FROM events ORDER BY id DESC LIMIT ?",
                            (limit,)).fetchall()
    return [dict(r) for r in rows]


def clear() -> None:
    with db.session() as conn:
        conn.execute("DELETE FROM events")
