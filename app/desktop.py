"""İzin verilen masaüstü işlemleri. Serbest shell komutu çalıştırmaz."""
from __future__ import annotations

import glob
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from .config import ROOT

CONFIG_PATH = ROOT / "config" / "apps.json"
EXAMPLE_PATH = ROOT / "config" / "apps.example.json"
ALLOWED_APPS = ("photoshop", "premiere", "lightroom", "capcut", "browser")

# config/apps.json içindeki yol bulunamazsa denenecek yaygın Windows 11 kurulum yolları.
# Joker karakterler (*) farklı Adobe sürümlerini (2024, 2025, 2026 ...) yakalar; en yeni sürüm seçilir.
CANDIDATES: dict[str, list[str]] = {
    "photoshop": [
        r"%ProgramFiles%\Adobe\Adobe Photoshop *\Photoshop.exe",
    ],
    "premiere": [
        r"%ProgramFiles%\Adobe\Adobe Premiere Pro *\Adobe Premiere Pro.exe",
    ],
    "lightroom": [
        r"%ProgramFiles%\Adobe\Adobe Lightroom Classic\Lightroom.exe",
        r"%ProgramFiles%\Adobe\Adobe Lightroom CC\lightroom.exe",
        r"%ProgramFiles%\Adobe\Adobe Lightroom\lightroom.exe",
    ],
    "capcut": [
        r"%LOCALAPPDATA%\CapCut\Apps\CapCut.exe",
        r"%ProgramFiles%\CapCut\CapCut.exe",
        r"%ProgramFiles(x86)%\CapCut\CapCut.exe",
    ],
    "browser": [
        r"%ProgramFiles%\Google\Chrome\Application\chrome.exe",
        r"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe",
        r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe",
        # Windows 11'de Microsoft Edge her zaman kuruludur; Chrome yoksa Edge açılır.
        r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe",
        r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe",
    ],
}


_WIN_VAR = re.compile(r"%([^%]+)%")


def _expand(path: str) -> str:
    """%LOCALAPPDATA% gibi Windows değişkenlerini ve $HOME/~ ifadelerini açar."""
    path = _WIN_VAR.sub(lambda m: os.environ.get(m.group(1), m.group(0)), path.strip())
    return os.path.normpath(os.path.expandvars(os.path.expanduser(path)))


def _load_config() -> dict[str, str]:
    path = CONFIG_PATH if CONFIG_PATH.exists() else EXAMPLE_PATH
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"{path.name} okunamadı veya geçersiz JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"{path.name} bir JSON nesnesi olmalı.")
    return {str(k): str(v) for k, v in data.items() if v}


def _first_existing(patterns: list[str]) -> str | None:
    for pattern in patterns:
        expanded = _expand(pattern)
        if "%" in expanded:  # tanımsız ortam değişkeni
            continue
        matches = sorted(glob.glob(expanded), reverse=True)  # en yeni sürüm önce
        for match in matches:
            if Path(match).is_file():
                return match
    return None


def resolve_app(app_id: str) -> str | None:
    """Uygulamanın çalıştırılabilir yolunu döndürür; bulunamazsa None."""
    if app_id not in ALLOWED_APPS:
        raise ValueError("Bu uygulama izin listesinde değil.")
    configured = _load_config().get(app_id)
    patterns = ([configured] if configured else []) + CANDIDATES.get(app_id, [])
    return _first_existing(patterns)


def app_status() -> dict[str, str | None]:
    """Her izinli uygulama için bulunan yol (veya None)."""
    result: dict[str, str | None] = {}
    for app_id in ALLOWED_APPS:
        try:
            result[app_id] = resolve_app(app_id)
        except ValueError:
            result[app_id] = None
    return result


def launch_app(app_id: str) -> str:
    target = resolve_app(app_id)
    if not target:
        configured = _load_config().get(app_id)
        raise FileNotFoundError(
            f"{app_id} bulunamadı. config/apps.json dosyasına doğru .exe yolunu yazın "
            f"(şu anki değer: {configured or 'boş'})."
        )
    kwargs: dict = {"cwd": str(Path(target).parent), "close_fds": True}
    if sys.platform == "win32":
        # Uygulama köprü penceresine bağlı kalmasın; köprü kapansa da açık kalsın.
        kwargs["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    subprocess.Popen([target], **kwargs)
    return f"{app_id} başlatıldı: {target}"
