"""Kalıcı hafıza: kullanıcı hakkında hatırlanacak bilgiler (yerel SQLite, tam metin arama)."""
from __future__ import annotations

import re
from datetime import datetime

from .. import db

KATEGORILER = ("profil", "tercih", "kisi", "proje", "not")


def _tokens(text: str) -> list[str]:
    text = text.replace("İ", "i").replace("I", "ı").lower()
    return [t for t in re.findall(r"\w+", text) if len(t) > 1]


def kaydet(bilgi: str, kategori: str = "not") -> dict:
    bilgi = bilgi.strip()
    if not bilgi:
        raise ValueError("Bilgi boş.")
    if kategori not in KATEGORILER:
        kategori = "not"
    with db.session() as conn:
        dup = conn.execute("SELECT id FROM memory WHERE lower(bilgi)=lower(?)", (bilgi,)).fetchone()
        if dup:
            return {"id": dup[0], "durum": "zaten kayıtlı"}
        cur = conn.execute("INSERT INTO memory (ts, kategori, bilgi) VALUES (?, ?, ?)",
                           (datetime.now().isoformat(timespec="seconds"), kategori, bilgi[:2000]))
        return {"id": cur.lastrowid, "durum": "kaydedildi"}


def ara(sorgu: str, limit: int = 8) -> dict:
    """Kelime örtüşmesine göre sıralı arama (Türkçe ek farkları için kök eşleşmesi: ilk 5 harf)."""
    q = {t[:5] for t in _tokens(sorgu)}
    with db.session() as conn:
        rows = conn.execute("SELECT id, ts, kategori, bilgi FROM memory").fetchall()
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
    with db.session() as conn:
        cur = conn.execute("DELETE FROM memory WHERE id=?", (int(kimlik),))
    return {"silindi": cur.rowcount > 0, "id": kimlik}


def tumu() -> list[dict]:
    with db.session() as conn:
        rows = conn.execute("SELECT id, ts, kategori, bilgi FROM memory ORDER BY id DESC").fetchall()
    return [{"id": r[0], "tarih": r[1][:10], "kategori": r[2], "bilgi": r[3]} for r in rows]


def baglam_ozeti(limit: int = 30) -> str:
    """Sistem istemine eklenecek kısa profil/tercih özeti."""
    with db.session() as conn:
        rows = conn.execute("SELECT kategori, bilgi FROM memory WHERE kategori IN ('profil','tercih','kisi','proje') "
                            "ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return "\n".join(f"- [{k}] {b}" for k, b in rows)
