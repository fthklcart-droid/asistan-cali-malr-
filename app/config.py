"""Ortak yapılandırma: .env yükleme ve proje yolları.

Hem yerel köprü (server) hem masaüstü arayüzü (ui) bu modülü kullanır; böylece
API anahtarları her iki süreçte de aynı .env dosyasından okunur.
"""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / ".env"
ENV_EXAMPLE_PATH = ROOT / ".env.example"

_loaded = False


def load_env() -> None:
    """.env dosyasını bir kez yükler (yoksa .env.example'a düşmez; anahtarlar boş kalır)."""
    global _loaded
    if _loaded:
        return
    if ENV_PATH.exists():
        # utf-8-sig: Windows Not Defteri'nin eklediği BOM karakterini tolere eder.
        load_dotenv(ENV_PATH, encoding="utf-8-sig")
    _loaded = True


def host() -> str:
    load_env()
    # Güvenlik: köprü yalnızca loopback üzerinde dinler.
    value = os.getenv("JARVIS_HOST", "127.0.0.1").strip() or "127.0.0.1"
    if value not in {"127.0.0.1", "localhost", "::1"}:
        value = "127.0.0.1"
    return value


def port() -> int:
    load_env()
    try:
        return int(os.getenv("JARVIS_PORT", "8765"))
    except ValueError:
        return 8765


def base_url() -> str:
    h = host()
    if h == "::1":
        h = "[::1]"
    return f"http://{h}:{port()}"


def has_key(name: str) -> bool:
    load_env()
    return bool(os.getenv(name, "").strip())
