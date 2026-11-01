"""FK JARVIS tek tık başlatıcı: yerel köprüyü arka planda açar, ardından arayüzü gösterir.

Kullanım:  python -m app.main
"""
from __future__ import annotations

import logging
import socket
import sys
import threading
import time

import requests

from .config import ROOT, base_url, host, load_env, port

LOG_PATH = ROOT / "jarvis.log"


def _bridge_running() -> bool:
    try:
        r = requests.get(base_url() + "/health", timeout=1)
        return r.ok and r.json().get("service") == "fk-jarvis-local-bridge"
    except Exception:
        return False


def _port_in_use() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port())) == 0


def start_bridge_in_background() -> str | None:
    """Köprüyü başlatır. Başarısızsa kullanıcıya gösterilecek hata metnini döndürür."""
    if _bridge_running():
        return None
    if _port_in_use():
        return (f"{port()} numaralı port başka bir program tarafından kullanılıyor. "
                ".env dosyasında JARVIS_PORT değerini değiştirin (örn. 8766).")

    import uvicorn
    from .server import app

    config = uvicorn.Config(app, host=host(), port=port(), log_level="warning", log_config=None)
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, name="jarvis-bridge", daemon=True)
    thread.start()
    for _ in range(50):  # en fazla ~10 sn bekle
        if _bridge_running():
            return None
        if not thread.is_alive():
            break
        time.sleep(0.2)
    return "Yerel köprü başlatılamadı. Ayrıntılar için jarvis.log dosyasına bakın."


def main() -> int:
    logging.basicConfig(
        filename=LOG_PATH, level=logging.INFO, encoding="utf-8",
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    load_env()
    error = start_bridge_in_background()

    from PySide6.QtWidgets import QApplication, QMessageBox
    from .ui import JarvisWindow

    qt_app = QApplication.instance() or QApplication(sys.argv)
    window = JarvisWindow()
    window.show()
    if error:
        logging.error(error)
        QMessageBox.warning(window, "FK JARVIS", error)
    return qt_app.exec()


def _fatal(message: str) -> None:
    """Konsolsuz (pythonw) çalışırken de hatayı kullanıcıya göster."""
    logging.exception(message)
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, f"{message}\n\nAyrıntılar: {LOG_PATH}", "FK JARVIS", 0x10)
            return
        except Exception:
            pass
    print(message, file=sys.stderr)


def cli(argv: list[str] | None = None) -> int:
    """Komut satırı girişi. `--selftest [rapor.json]` kendi kendini sınar (paket doğrulaması için)."""
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "--selftest":
        from .selftest import run
        return run(args[1] if len(args) > 1 else None)
    try:
        return main()
    except SystemExit:
        raise
    except Exception as exc:
        _fatal(f"FK JARVIS başlatılamadı: {type(exc).__name__}: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(cli())
