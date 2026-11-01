"""Kurulum kontrolü:  python -m app.doctor

Python sürümünü, paketleri, .env anahtarlarını, uygulama yollarını ve ses aygıtlarını denetler.
API anahtarlarının değerini asla yazdırmaz.
"""
from __future__ import annotations

import importlib
import sys

OK, WARN, FAIL = "[ OK ]", "[UYARI]", "[HATA]"


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # eski kod sayfalı konsollarda çökmesin
        except Exception:
            pass
    problems = 0
    print("FK JARVIS kurulum kontrolü\n" + "-" * 40)

    v = sys.version_info
    if v >= (3, 10):
        print(f"{OK} Python {v.major}.{v.minor}.{v.micro}")
    else:
        print(f"{FAIL} Python {v.major}.{v.minor} çok eski; 3.11 veya 3.12 kurun.")
        problems += 1

    for module, label in [
        ("fastapi", "FastAPI"), ("uvicorn", "Uvicorn"), ("anthropic", "Anthropic SDK"),
        ("dotenv", "python-dotenv"), ("PySide6.QtWidgets", "PySide6"), ("requests", "requests"),
        ("google.genai", "Google GenAI"), ("sounddevice", "sounddevice"), ("speech_recognition", "SpeechRecognition"),
    ]:
        try:
            importlib.import_module(module)
            print(f"{OK} {label}")
        except Exception as exc:
            print(f"{FAIL} {label} yüklenemedi: {exc}")
            problems += 1

    from .config import ENV_PATH, has_key, port
    if ENV_PATH.exists():
        print(f"{OK} .env bulundu")
    else:
        print(f"{WARN} .env yok — setup_windows.bat çalıştırın veya .env.example'ı .env olarak kopyalayın.")
    print(f"{OK if has_key('ANTHROPIC_API_KEY') else WARN} ANTHROPIC_API_KEY {'girilmiş' if has_key('ANTHROPIC_API_KEY') else 'boş (Claude planlama çalışmaz)'}")
    print(f"{OK if has_key('GEMINI_API_KEY') else WARN} GEMINI_API_KEY {'girilmiş' if has_key('GEMINI_API_KEY') else 'boş (Gemini sesli görüşme çalışmaz)'}")
    print(f"{OK} Köprü portu: {port()}")

    try:
        from .desktop import app_status
        for app_id, path in app_status().items():
            print(f"{OK if path else WARN} {app_id}: {path or 'bulunamadı (config/apps.json içine yolu yazın)'}")
    except Exception as exc:
        print(f"{FAIL} config/apps.json okunamadı: {exc}")
        problems += 1

    try:
        import sounddevice as sd
        sd.query_devices(kind="input")
        print(f"{OK} Mikrofon bulundu")
    except Exception as exc:
        print(f"{WARN} Mikrofon bulunamadı: {exc}")
    try:
        import sounddevice as sd
        sd.query_devices(kind="output")
        print(f"{OK} Hoparlör bulundu")
    except Exception as exc:
        print(f"{WARN} Hoparlör bulunamadı: {exc}")

    print("-" * 40)
    print("Sonuç: " + ("hazır." if problems == 0 else f"{problems} kritik sorun var."))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
