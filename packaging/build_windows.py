"""Windows paketi oluşturur: PyInstaller ile `dist/FK JARVIS/` klasörü (+ isteğe bağlı Inno Setup kurulumu).

Kullanım (proje kökünden, Windows'ta):
    python packaging/build_windows.py            # yalnız .exe klasörü
    python packaging/build_windows.py --installer  # + dist/installer/FK_JARVIS_Kurulum_<sürüm>.exe (Inno Setup 6 gerekir)
build_exe.bat ve GitHub Actions (.github/workflows/windows.yml) bu betiği kullanır.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist" / "FK JARVIS"
NAME = "FK JARVIS"

# Yalnızca çalışma anında (fonksiyon içinde) içe aktarılan modüller: PyInstaller bunları kendisi göremez.
HIDDEN_IMPORTS = [
    "app.selftest", "app.sohbet", "app.computer_agent", "app.executor", "app.gemini_voice", "app.agent_worker",
    "app.agent_overlay", "app.windows_voice_commands", "app.konusma",
    "pyttsx3.drivers", "pyttsx3.drivers.sapi5", "comtypes.client",
    "pynput.keyboard._win32", "pynput.mouse._win32",
    "win32com.client", "pythoncom", "pywintypes",
]
COLLECT_SUBMODULES = ["app", "uvicorn", "anthropic", "google.genai"]
COLLECT_DATA = ["speech_recognition", "googleapiclient", "certifi"]


def version() -> str:
    text = (ROOT / "app" / "server.py").read_text(encoding="utf-8")
    return re.search(r'VERSION = "([^"]+)"', text).group(1)


def build_exe() -> Path:
    args = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--windowed", "--name", NAME,
            "--distpath", str(ROOT / "dist"), "--workpath", str(ROOT / "build"), "--specpath", str(ROOT / "build")]
    for m in HIDDEN_IMPORTS:
        args += ["--hidden-import", m]
    for m in COLLECT_SUBMODULES:
        args += ["--collect-submodules", m]
    for m in COLLECT_DATA:
        args += ["--collect-data", m]
    args.append(str(ROOT / "jarvis.pyw"))
    print(">", " ".join(args), flush=True)
    subprocess.run(args, check=True, cwd=ROOT)

    # Ayar şablonları .exe'nin yanına (uygulama ROOT olarak .exe klasörünü kullanır). .env ve data/ KOPYALANMAZ.
    (DIST / "config").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / ".env.example", DIST / ".env.example")
    shutil.copy2(ROOT / "config" / "apps.example.json", DIST / "config" / "apps.example.json")
    for doc in ("README.md",):
        shutil.copy2(ROOT / doc, DIST / doc)
    (DIST / "VERSION.txt").write_text(version() + "\n", encoding="utf-8")
    exe = DIST / f"{NAME}.exe"
    if not exe.exists():
        raise SystemExit(f"Beklenen dosya oluşmadı: {exe}")
    print(f"[OK] {exe}")
    return exe


def find_iscc() -> str | None:
    candidates = [shutil.which("iscc"), shutil.which("ISCC"),
                  os.path.expandvars(r"%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"),
                  os.path.expandvars(r"%ProgramFiles%\Inno Setup 6\ISCC.exe"),
                  os.path.expandvars(r"%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe")]
    return next((c for c in candidates if c and Path(c).is_file()), None)


def build_installer() -> Path:
    iscc = find_iscc()
    if not iscc:
        raise SystemExit("Inno Setup 6 bulunamadı (https://jrsoftware.org/isdl.php veya: winget install JRSoftware.InnoSetup).")
    ver = version()
    subprocess.run([iscc, f"/DAppVersion={ver}", f"/DSourceDir={DIST}",
                    f"/DOutputDir={ROOT / 'dist' / 'installer'}", str(ROOT / "packaging" / "fk_jarvis.iss")],
                   check=True, cwd=ROOT)
    out = ROOT / "dist" / "installer" / f"FK_JARVIS_Kurulum_{ver}.exe"
    if not out.exists():
        raise SystemExit(f"Kurulum dosyası oluşmadı: {out}")
    print(f"[OK] {out}")
    return out


if __name__ == "__main__":
    build_exe()
    if "--installer" in sys.argv:
        build_installer()
