"""Salt-okunur dosya becerileri + notlar. Gizli/kimlik dosyaları ve sistem klasörleri asla okunmaz."""
from __future__ import annotations

import fnmatch
import os
import re
from datetime import datetime
from pathlib import Path

from ..config import ROOT

NOTES_DIR = ROOT / "data" / "notlar"
MAX_READ = 60_000
TEXT_EXT = {".txt", ".md", ".csv", ".json", ".log", ".py", ".js", ".ts", ".html", ".css", ".xml", ".yaml",
            ".yml", ".ini", ".cfg", ".toml", ".bat", ".ps1", ".sql", ".srt", ".tsv"}
BLOCKED_NAMES = {".env", "id_rsa", "id_ed25519", "known_hosts", "credentials", "credentials.json",
                 "youtube_token.json", "client_secret.json", "login data", "cookies", "web data", "key4.db",
                 "logins.json", "ntuser.dat"}
BLOCKED_PATTERNS = ["*.pem", "*.key", "*.pfx", "*.p12", "*.kdbx", "*.wallet", "*secret*", "*token*", "*password*",
                    "*şifre*", "*sifre*", ".env.*"]
BLOCKED_DIRS = {".ssh", ".gnupg", ".aws", ".azure", "appdata", "windows", "program files", "program files (x86)",
                "programdata", "$recycle.bin", "system volume information"}


class DosyaHatasi(ValueError):
    pass


def _roots() -> list[Path]:
    """İzinli kökler: kullanıcının seçtiği çalışma klasörleri (Ayarlar) + JARVIS veri klasörü."""
    from .. import workspaces
    roots = workspaces.roots() + [(ROOT / "data").resolve()]
    return [r for r in roots if r.exists()]


def guvenli_yol(yol: str) -> Path:
    raw = os.path.expandvars(str(yol).strip().strip('"'))
    if raw == "~" or raw.startswith(("~/", "~\\")):
        raw = str(Path.home() / raw[2:]) if len(raw) > 1 else str(Path.home())
    p = Path(raw)
    if not p.is_absolute():
        p = Path.home() / p
    p = p.resolve()
    if not any(p == r or r in p.parents for r in _roots()):
        raise DosyaHatasi("Bu konum çalışma klasörlerinizin dışında. ⚙ Ayarlar > Çalışma klasörleri'nden ekleyebilirsiniz.")
    parts = {x.lower() for x in p.parts}
    if parts & BLOCKED_DIRS:
        raise DosyaHatasi("Sistem veya gizli klasörlere erişilemez.")
    name = p.name.lower()
    if name in BLOCKED_NAMES or any(fnmatch.fnmatch(name, pat) for pat in BLOCKED_PATTERNS):
        raise DosyaHatasi("Bu dosya gizli bilgi içerebileceği için okunamaz.")
    return p


def _boyut(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def listele(klasor: str = "~", desen: str = "*") -> dict:
    p = guvenli_yol(klasor)
    if not p.is_dir():
        raise DosyaHatasi(f"Klasör değil: {p}")
    items = []
    for child in sorted(p.iterdir(), key=lambda c: (not c.is_dir(), c.name.lower())):
        if child.name.startswith(".") or not fnmatch.fnmatch(child.name.lower(), desen.lower()):
            continue
        try:
            st = child.stat()
        except OSError:
            continue
        items.append({"ad": child.name + ("/" if child.is_dir() else ""),
                      "boyut": "" if child.is_dir() else _boyut(st.st_size),
                      "degisim": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")})
        if len(items) >= 200:
            break
    return {"klasor": str(p), "ogeler": items, "adet": len(items)}


def bilgi(yol: str) -> dict:
    p = guvenli_yol(yol)
    if not p.exists():
        return {"yol": str(p), "var": False}
    st = p.stat()
    return {"yol": str(p), "var": True, "tur": "klasör" if p.is_dir() else "dosya", "boyut": _boyut(st.st_size),
            "degisim": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")}


def oku(yol: str, baslangic: int = 0) -> dict:
    p = guvenli_yol(yol)
    if not p.is_file():
        raise DosyaHatasi(f"Dosya bulunamadı: {p}")
    if p.suffix.lower() not in TEXT_EXT:
        raise DosyaHatasi("Yalnızca metin dosyaları okunabilir (görseller için gorsel_incele kullanılır).")
    text = p.read_text(encoding="utf-8", errors="replace")
    start = max(0, int(baslangic or 0))
    chunk = text[start:start + MAX_READ]
    out = {"yol": str(p), "icerik": chunk, "toplam_karakter": len(text)}
    if start + MAX_READ < len(text):
        out["devami_icin_baslangic"] = start + MAX_READ
    return out


def ara(klasor: str, desen: str) -> dict:
    """Klasör ve alt klasörlerinde ada göre dosya arar (en fazla 50 sonuç, 4 seviye derinlik)."""
    base = guvenli_yol(klasor)
    if not base.is_dir():
        raise DosyaHatasi(f"Klasör değil: {base}")
    pat = desen if any(c in desen for c in "*?") else f"*{desen}*"
    found = []
    for dirpath, dirnames, filenames in os.walk(base):
        depth = len(Path(dirpath).relative_to(base).parts)
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d.lower() not in BLOCKED_DIRS and depth < 4]
        for f in filenames:
            if fnmatch.fnmatch(f.lower(), pat.lower()):
                found.append(str(Path(dirpath) / f))
                if len(found) >= 50:
                    return {"sonuclar": found, "not": "İlk 50 sonuç."}
    return {"sonuclar": found}


def not_yaz(baslik: str, icerik: str, ekle: bool = False) -> dict:
    name = re.sub(r"[^\w\- ]", "", baslik, flags=re.UNICODE).strip()[:80] or "not"
    NOTES_DIR.mkdir(parents=True, exist_ok=True)
    path = NOTES_DIR / f"{name}.md"
    mode = "a" if ekle and path.exists() else "w"
    with path.open(mode, encoding="utf-8") as fh:
        if mode == "a":
            fh.write("\n\n")
        fh.write(icerik.strip() + "\n")
    return {"yol": str(path), "durum": "eklendi" if mode == "a" else "yazıldı"}


def notlari_listele() -> dict:
    NOTES_DIR.mkdir(parents=True, exist_ok=True)
    return {"notlar": [p.stem for p in sorted(NOTES_DIR.glob("*.md"))]}


def not_oku(baslik: str) -> dict:
    name = re.sub(r"[^\w\- ]", "", baslik, flags=re.UNICODE).strip()
    path = NOTES_DIR / f"{name}.md"
    if not path.is_file():
        raise DosyaHatasi(f"Not bulunamadı: {baslik}")
    return {"baslik": name, "icerik": path.read_text(encoding="utf-8")[:MAX_READ]}
