"""Cihaz kimliği ve yerel API erişim anahtarı.

İlk çalıştırmada data/device.json oluşturulur: {device_id, token, created_at}.
Yerel köprüye (127.0.0.1) yapılan her istek `X-Jarvis-Token` başlığıyla bu anahtarı taşımalıdır;
böylece aynı bilgisayardaki başka bir program veya tarayıcıdaki bir web sayfası köprüyü kullanamaz.
"""
from __future__ import annotations

import json
import os
import secrets
import threading
import uuid
from datetime import datetime
from pathlib import Path

from .config import ROOT

DEVICE_PATH: Path = ROOT / "data" / "device.json"
HEADER = "X-Jarvis-Token"
_lock = threading.Lock()
_cache: dict | None = None


def _load() -> dict:
    global _cache
    path = Path(DEVICE_PATH)
    with _lock:
        if _cache and _cache.get("_path") == str(path):
            return _cache
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
        else:
            data = {"device_id": str(uuid.uuid4()), "token": secrets.token_urlsafe(32),
                    "created_at": datetime.now().isoformat(timespec="seconds")}
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
            if os.name != "nt":
                os.chmod(tmp, 0o600)
            tmp.replace(path)
        data["_path"] = str(path)
        _cache = data
        return data


def device_id() -> str:
    return _load()["device_id"]


def token() -> str:
    return _load()["token"]


def check(candidate: str | None) -> bool:
    if not candidate:
        return False
    # Bayt karşılaştırması: compare_digest ASCII dışı str ile hata fırlatır (ör. "yanlış").
    return secrets.compare_digest(candidate.encode("utf-8"), token().encode("utf-8"))


def auth_headers() -> dict[str, str]:
    return {HEADER: token()}


def reset_cache() -> None:
    global _cache
    with _lock:
        _cache = None
