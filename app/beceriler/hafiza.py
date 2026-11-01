"""Kalıcı hafıza: kullanıcı hakkında hatırlanacak bilgiler (yerel SQLite, tam metin arama)."""
from __future__ import annotations

import re
import sqlite3
import threading
from datetime import datetime
from pathlib import Path

from ..config import ROOT

DB_PATH = ROOT / "data" / "hafiza.db"
KATEGORILER = ("profil", "tercih", "kisi", "proje", "not")
_lock = threading.Lock()


def _db(path: Path | None = None) -> sqlite3.Connection:
    path = path or DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10)
    conn.execute("""CREATE TABLE IF NOT EXISTS hafiza (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, kategori TEXT NOT NULL, bilgi TEXT NOT NULL)""")
    return conn


def _tokens(text: str) -> list[str]:
    text = text.replace("İ", "i").replace("I", "ı").lower()
    return [t for t in re.findall(r"\w+", text) if len(t) > 1]


def kaydet(bilgi: str, kategori: str = "not") -> dict:
    bilgi = bilgi.strip()
    if not bilgi:
        raise ValueError("Bilgi boş.")
    if kategori not in KATEGORILER:
        kategori = "not"
    with _lock, _db() as conn:
        dup = conn.execute("SELECT id FROM hafiza WHERE lower(bilgi)=lower(?)", (bilgi,)).fetchone()
        if dup:
            return {"id": dup[0], "durum": "zaten kayıtlı"}
        cur = conn.execute("INSERT INTO hafiza (ts, kategori, bilgi) VALUES (?, ?, ?)",
                           (datetime.now().isoformat(timespec="seconds"), kategori, bilgi[:2000]))
        return {"id": cur.lastrowid, "durum": "kaydedildi"}


def ara(sorgu: str, limit: int = 8) -> dict:
    """Kelime örtüşmesine göre sıralı arama (Türkçe ek farkları için kök eşleşmesi: ilk 5 harf)."""
    q = {t[:5] for t in _tokens(sorgu)}
    with _lock, _db() as conn:
        rows = conn.execute("SELECT id, ts, kategori, bilgi FROM hafiza").fetchall()
    scored = []
    for r in rows:
        words = {t[:5] for t in _tokens(r[3] + " " + r[2])}
        score = len(q & words)
        if score or not q:
            scored.append((score, r))
    scored.sort(key=lambda x: (-x[0], -x[1][0]))
    return {"sonuclar": [{"id": r[0], "tarih": r[1][:10], "kategori": r[2], "bilgi": r[3]}
                         for _, r in scored[:max(1, min(20, limit))]]}


def sil(kimlik: int) -> dict:
    with _lock, _db() as conn:
        cur = conn.execute("DELETE FROM hafiza WHERE id=?", (int(kimlik),))
    return {"silindi": cur.rowcount > 0, "id": kimlik}


def tumu() -> list[dict]:
    with _lock, _db() as conn:
        rows = conn.execute("SELECT id, ts, kategori, bilgi FROM hafiza ORDER BY id DESC").fetchall()
    return [{"id": r[0], "tarih": r[1][:10], "kategori": r[2], "bilgi": r[3]} for r in rows]


def baglam_ozeti(limit: int = 30) -> str:
    """Sistem istemine eklenecek kısa profil/tercih özeti."""
    with _lock, _db() as conn:
        rows = conn.execute("SELECT kategori, bilgi FROM hafiza WHERE kategori IN ('profil','tercih','kisi','proje') "
                            "ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return "\n".join(f"- [{k}] {b}" for k, b in rows)
