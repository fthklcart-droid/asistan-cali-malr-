"""Kendi kendini sınama: `FK JARVIS.exe --selftest [rapor.json]` veya `python -m app.main --selftest`.

Paketlenmiş (.exe) sürümün gerçekten çalıştığını doğrular: PyInstaller'ın gözden kaçırabileceği, yalnızca
gerektiğinde içe aktarılan modüller, veritabanı göçleri, yerel köprü (gerçek HTTP + jeton) ve arayüzün
ekransız kurulması. Gerçek kullanıcı verisine dokunmaz (geçici klasör kullanır).
Çıkış kodu: 0 = başarılı, 1 = başarısız. Rapor JSON olarak yazılır (konsolsuz .exe'de stdout yoktur).
"""
from __future__ import annotations

import importlib
import json
import os
import socket
import sys
import tempfile
import time
import traceback
from pathlib import Path

# Her platformda yüklenebilmesi gereken modüller (çoğu yalnızca bir özellik kullanılınca içe aktarılır)
MODULES = [
    "app.server", "app.ui", "app.sohbet", "app.computer_agent", "app.gemini_voice", "app.agent_worker",
    "app.agent_overlay", "app.windows_voice_commands", "app.konusma", "app.connectors.whatsapp",
    "app.connectors.youtube", "app.connectors.photoshop", "app.beceriler.gorsel",
    "anthropic", "google.genai", "speech_recognition", "sounddevice", "mss", "PIL.Image", "pyttsx3",
    "googleapiclient.discovery", "google_auth_oauthlib.flow", "uvicorn.loops.auto", "uvicorn.protocols.http.auto",
    "uvicorn.lifespan.on",
]
# Yalnız Windows'ta zorunlu (Linux'ta ekran sunucusu/COM gerektirir)
WINDOWS_MODULES = ["pyautogui", "pyperclip", "pynput.keyboard", "win32com.client", "pythoncom",
                   "pyttsx3.drivers.sapi5", "comtypes.client"]


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(report_path: str | None = None) -> int:
    report: dict = {"frozen": bool(getattr(sys, "frozen", False)), "python": sys.version.split()[0],
                    "platform": sys.platform, "checks": {}, "ok": False}
    checks = report["checks"]

    def check(name: str, fn):
        try:
            value = fn()
            checks[name] = {"ok": True, "detail": value}
        except BaseException as exc:  # noqa: BLE001 — her hatayı rapora yaz
            checks[name] = {"ok": False, "detail": f"{type(exc).__name__}: {exc}",
                            "trace": traceback.format_exc()[-1500:]}

    os.environ["QT_QPA_PLATFORM"] = os.environ.get("JARVIS_SELFTEST_QPA", "offscreen")
    tmp = Path(tempfile.mkdtemp(prefix="fkjarvis-selftest-"))
    os.environ["JARVIS_PORT"] = str(_free_port())

    from . import db, device
    db.DB_PATH = tmp / "jarvis.db"
    device.DEVICE_PATH = tmp / "device.json"
    device.reset_cache()

    required = MODULES + (WINDOWS_MODULES if sys.platform == "win32" else [])
    for mod in required:
        check(f"import:{mod}", lambda m=mod: importlib.import_module(m) and "ok")

    check("database", lambda: f"şema sürümü {db.migrate(db.DB_PATH)}")

    def bridge():
        from .main import start_bridge_in_background
        err = start_bridge_in_background()
        if err:
            raise RuntimeError(err)
        from . import api_client
        system = api_client.get("/api/v1/system")
        task = api_client.post("/api/v1/tasks", {"instruction": "Kendi kendini sınama görevi"})
        api_client.post(f"/api/v1/tasks/{task['id']}/cancel")
        dash = api_client.get("/api/v1/dashboard")
        assert dash["audit"]["ok"], "denetim zinciri bozuk"
        import requests
        from .config import base_url
        assert requests.get(base_url() + "/api/v1/system", timeout=5).status_code == 401, "jetonsuz istek kabul edildi"
        return f"köprü {system['version']} • şema {system['schema_version']} • görev oluştur/iptal ✔"
    check("bridge", bridge)

    def window():
        from PySide6.QtWidgets import QApplication
        from .ui import JarvisWindow
        app = QApplication.instance() or QApplication(sys.argv[:1])
        w = JarvisWindow()
        w.show()
        deadline = time.time() + 3
        while time.time() < deadline:
            app.processEvents()
            time.sleep(0.05)
        pages = [w.tabs.tabText(i).strip() for i in range(w.tabs.count())]
        w.close()
        app.processEvents()
        return f"{len(pages)} sayfa"
    check("window", window)

    report["ok"] = all(c["ok"] for c in checks.values())
    report["failed"] = [k for k, c in checks.items() if not c["ok"]]
    target = Path(report_path) if report_path else Path(tempfile.gettempdir()) / "fk_jarvis_selftest.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if sys.stdout:
        print(("SELFTEST OK" if report["ok"] else "SELFTEST FAILED: " + ", ".join(report["failed"]))
              + f"  (rapor: {target})")
    return 0 if report["ok"] else 1
