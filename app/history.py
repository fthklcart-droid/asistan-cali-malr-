"""Görev ve eylem geçmişi (yerel SQLite: data/jarvis.db)."""
from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime
from pathlib import Path

from .config import ROOT

DB_PATH = ROOT / "data" / "jarvis.db"
_lock = threading.Lock()


def _connect(path: Path | None = None) -> sqlite3.Connection:
    path = path or DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            detail TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT ''
        )"""
    )
    return conn


def log(kind: str, title: str, detail: str | dict = "", status: str = "") -> int:
    if not isinstance(detail, str):
        detail = json.dumps(detail, ensure_ascii=False)
    with _lock, _connect() as conn:
        cur = conn.execute(
            "INSERT INTO events (ts, kind, title, detail, status) VALUES (?, ?, ?, ?, ?)",
            (datetime.now().isoformat(timespec="seconds"), kind, title[:500], detail[:20000], status),
        )
        return int(cur.lastrowid)


def recent(limit: int = 200) -> list[dict]:
    with _lock, _connect() as conn:
        rows = conn.execute(
            "SELECT id, ts, kind, title, detail, status FROM events ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    return [dict(zip(("id", "ts", "kind", "title", "detail", "status"), r)) for r in rows]


def clear() -> None:
    with _lock, _connect() as conn:
        conn.execute("DELETE FROM events")
