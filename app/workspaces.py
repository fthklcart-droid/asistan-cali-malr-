"""Çalışma klasörleri: dosya becerilerinin erişebildiği klasörler (kullanıcı seçer).

İlk kullanımda Windows'un bilinen klasörleri (Masaüstü, Belgeler, İndirilenler, Resimler, Videolar, Müzik)
eklenir; OneDrive'a yönlendirilmiş klasörler de doğru bulunur. Ayarlar sayfasından eklenip çıkarılabilir.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from . import audit, db

KNOWN_FOLDERS = {  # Windows KNOWNFOLDERID
    "Masaüstü": ("{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}", "Desktop"),
    "Belgeler": ("{FDD39AD0-238F-46AF-ADB4-6C85480369C7}", "Documents"),
    "İndirilenler": ("{374DE290-123F-4565-9164-39C4925E467B}", "Downloads"),
    "Resimler": ("{33E28130-4E1E-4676-835A-98395C3BC3BB}", "Pictures"),
    "Videolar": ("{18989B1D-99B5-455B-841C-AB7C74E4DDFC}", "Videos"),
    "Müzik": ("{4BD8D571-6D19-48D3-BE97-422220080E43}", "Music"),
}


def _known_folder(guid: str) -> Path | None:
    if sys.platform != "win32":
        return None
    try:
        import ctypes
        from ctypes import wintypes
        import uuid

        class GUID(ctypes.Structure):
            _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD), ("Data3", wintypes.WORD),
                        ("Data4", ctypes.c_ubyte * 8)]
        u = uuid.UUID(guid)
        g = GUID(u.fields[0], u.fields[1], u.fields[2], (ctypes.c_ubyte * 8)(*u.bytes[8:]))
        out = ctypes.c_wchar_p()
        if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(out)) != 0:
            return None
        path = Path(out.value)
        ctypes.windll.ole32.CoTaskMemFree(out)
        return path
    except Exception:
        return None


def default_folders() -> list[tuple[str, Path]]:
    found = []
    for label, (guid, name) in KNOWN_FOLDERS.items():
        p = _known_folder(guid) or Path.home() / name
        if p.is_dir():
            found.append((label, p))
    return found


def _seed_if_empty(conn) -> None:
    if conn.execute("SELECT COUNT(*) FROM workspaces").fetchone()[0] or db.get_setting("workspaces.seeded") == "1":
        return
    for label, p in default_folders():
        conn.execute("INSERT OR IGNORE INTO workspaces (path, label, created_at) VALUES (?, ?, ?)",
                     (str(p.resolve()), label, db.now()))


def list_all() -> list[dict]:
    with db.session() as conn:
        _seed_if_empty(conn)
        rows = conn.execute("SELECT id, path, label, created_at FROM workspaces ORDER BY id").fetchall()
    db.set_setting("workspaces.seeded", "1")
    return [dict(r) for r in rows]


def add(path: str, label: str = "", actor: str = "kullanici") -> dict:
    p = Path(os.path.expandvars(os.path.expanduser(path.strip().strip('"'))))
    if not p.is_dir():
        raise ValueError(f"Klasör bulunamadı: {p}")
    p = p.resolve()
    if p == Path(p.anchor):
        raise ValueError("Bir sürücünün tamamı çalışma klasörü yapılamaz; alt klasör seçin.")
    list_all()  # varsayılanlar bir kez eklensin
    with db.session() as conn:
        conn.execute("INSERT OR IGNORE INTO workspaces (path, label, created_at) VALUES (?, ?, ?)",
                     (str(p), label or p.name, db.now()))
        row = conn.execute("SELECT id, path, label, created_at FROM workspaces WHERE path = ?", (str(p),)).fetchone()
    audit.record("calisma_klasoru_eklendi", str(p), {}, actor)
    return dict(row)


def remove(workspace_id: int, actor: str = "kullanici") -> bool:
    list_all()
    with db.session() as conn:
        row = conn.execute("SELECT path FROM workspaces WHERE id = ?", (int(workspace_id),)).fetchone()
        if not row:
            return False
        conn.execute("DELETE FROM workspaces WHERE id = ?", (int(workspace_id),))
    audit.record("calisma_klasoru_kaldirildi", row[0], {}, actor)
    return True


def roots() -> list[Path]:
    paths = [Path(w["path"]) for w in list_all()]
    extra = os.getenv("JARVIS_EXTRA_FOLDERS", "")
    paths += [Path(p.strip()) for p in extra.split(";") if p.strip()]
    return [p.resolve() for p in paths if p.exists()]
