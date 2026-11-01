"""Ayarlar ekranının .env ve config/apps.json dosyalarını güvenle güncellemesi."""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

from . import config
from .desktop import ALLOWED_APPS, CONFIG_PATH, EXAMPLE_PATH

# Arayüzde düzenlenebilen anahtarlar (sıra = ekrandaki sıra)
FIELDS: list[tuple[str, str, bool]] = [
    # (anahtar, Türkçe etiket, gizli mi)
    ("ANTHROPIC_API_KEY", "Claude API anahtarı", True),
    ("ANTHROPIC_MODEL", "Claude modeli (boş = claude-opus-5-5)", False),
    ("GEMINI_API_KEY", "Gemini API anahtarı", True),
    ("GEMINI_LIVE_MODEL", "Gemini Live modeli (boş = otomatik)", False),
    ("GEMINI_VOICE_NAME", "Gemini ses adı", False),
    ("AUDIO_INPUT_DEVICE", "Mikrofon (boş = varsayılan)", False),
    ("AUDIO_OUTPUT_DEVICE", "Hoparlör (boş = varsayılan)", False),
    ("WHATSAPP_TOKEN", "WhatsApp Cloud API erişim anahtarı", True),
    ("WHATSAPP_PHONE_NUMBER_ID", "WhatsApp telefon numarası kimliği", False),
    ("WHATSAPP_API_VERSION", "Graph API sürümü (boş = v23.0)", False),
    ("YOUTUBE_CLIENT_SECRET_FILE", "YouTube OAuth client_secret.json yolu", False),
    ("JARVIS_PORT", "Yerel köprü portu", False),
]
SECRET_KEYS = {k for k, _, secret in FIELDS if secret}

_LINE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=")


def read_env() -> dict[str, str]:
    config.load_env()
    return {key: os.getenv(key, "") for key, _, _ in FIELDS}


def _quote(value: str) -> str:
    if value == "" or re.fullmatch(r"[A-Za-z0-9_./:\\@+,-]*", value):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def write_env(values: dict[str, str], path: Path | None = None) -> None:
    """Var olan satırları/yorumları koruyarak .env dosyasını günceller ve süreç ortamına uygular."""
    path = path or config.ENV_PATH
    before = {k: os.getenv(k, "") for k in values}
    if path.exists():
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    elif config.ENV_EXAMPLE_PATH.exists():
        lines = config.ENV_EXAMPLE_PATH.read_text(encoding="utf-8-sig").splitlines()
    else:
        lines = []
    remaining = {k: v.strip() for k, v in values.items()}
    for i, line in enumerate(lines):
        m = _LINE.match(line)
        if m and m.group(1) in remaining:
            key = m.group(1)
            lines[i] = f"{key}={_quote(remaining.pop(key))}"
    for key, value in remaining.items():
        lines.append(f"{key}={_quote(value)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    for key, value in values.items():
        os.environ[key] = value.strip()
    changed = sorted(k for k, v in values.items() if before.get(k, "") != v.strip())
    if changed:
        from . import audit  # yalnızca değişen ayarların ADLARI yazılır, değerleri asla
        audit.record("ayarlar_degisti", ".env", {"keys": changed})


def read_apps() -> dict[str, str]:
    path = CONFIG_PATH if CONFIG_PATH.exists() else EXAMPLE_PATH
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError):
        data = {}
    return {app_id: str(data.get(app_id, "")) for app_id in ALLOWED_APPS}


def write_apps(apps: dict[str, str]) -> None:
    clean = {k: v.strip() for k, v in apps.items() if k in ALLOWED_APPS}
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(clean, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def audio_devices() -> tuple[list[str], list[str]]:
    """(mikrofonlar, hoparlörler) adları. sounddevice yoksa boş listeler."""
    try:
        import sounddevice as sd
        devices = sd.query_devices()
        hostapis = sd.query_hostapis()
    except Exception:
        return [], []
    inputs, outputs = [], []
    for d in devices:
        api = hostapis[d["hostapi"]]["name"]
        # Windows'ta aynı aygıt birden çok sürücüyle listelenir; en uyumlu olanları göster.
        if os.name == "nt" and api not in ("MME", "Windows WASAPI"):
            continue
        name = d["name"]
        if d["max_input_channels"] > 0 and name not in inputs:
            inputs.append(name)
        if d["max_output_channels"] > 0 and name not in outputs:
            outputs.append(name)
    return inputs, outputs


def device_arg(env_key: str):
    """sounddevice 'device' parametresi: ayarlıysa ad, değilse None (varsayılan)."""
    config.load_env()
    value = os.getenv(env_key, "").strip()
    return value or None
