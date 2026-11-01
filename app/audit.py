"""Denetim kaydı: güvenlikle ilgili her olay için eklemeli (append-only), hash zincirli kayıt.

Her satırın hash'i = SHA-256(önceki_hash + ts + actor + event + target + data). Bir satır sonradan
değiştirilir veya silinirse `verify()` zincirin kırıldığı satırı bildirir.
"""
from __future__ import annotations

import hashlib
import json

from . import db

GENESIS = "0" * 64


def _digest(prev: str, ts: str, actor: str, event: str, target: str, data: str) -> str:
    payload = "\x1f".join((prev, ts, actor, event, target, data))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def record(event: str, target: str = "", data: dict | str | None = None, actor: str = "kullanici") -> int:
    """Olayı kaydeder. Gizli bilgi (API anahtarı vb.) data içine KONMAMALIDIR."""
    text = data if isinstance(data, str) else json.dumps(data or {}, ensure_ascii=False, sort_keys=True)
    with db.session() as conn:  # BEGIN IMMEDIATE: zincir ucu okunurken başka yazar giremez
        row = conn.execute("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1").fetchone()
        prev = row[0] if row else GENESIS
        ts = db.now()
        h = _digest(prev, ts, actor, event, target, text)
        cur = conn.execute(
            "INSERT INTO audit_log (ts, actor, event, target, data, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (ts, actor, event, target[:500], text[:20000], prev, h))
        return int(cur.lastrowid)


def recent(limit: int = 200) -> list[dict]:
    with db.session(write=False) as conn:
        rows = conn.execute("SELECT id, ts, actor, event, target, data FROM audit_log ORDER BY id DESC LIMIT ?",
                            (limit,)).fetchall()
    return [dict(r) for r in rows]


def verify() -> dict:
    """Zinciri baştan sona doğrular."""
    prev = GENESIS
    count = 0
    with db.session(write=False) as conn:
        for r in conn.execute("SELECT id, ts, actor, event, target, data, prev_hash, hash FROM audit_log ORDER BY id"):
            count += 1
            if r["prev_hash"] != prev or _digest(prev, r["ts"], r["actor"], r["event"], r["target"], r["data"]) != r["hash"]:
                return {"ok": False, "entries": count, "broken_at": r["id"]}
            prev = r["hash"]
    return {"ok": True, "entries": count, "broken_at": None}
