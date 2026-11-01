@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title FK JARVIS EXE derleme
if not exist ".venv\Scripts\python.exe" (
  echo Once setup_windows.bat calistirin.
  pause
  exit /b 1
)
set "VPY=.venv\Scripts\python.exe"
echo PyInstaller kuruluyor...
"%VPY%" -m pip install --disable-pip-version-check -q "pyinstaller>=6.10"
if errorlevel 1 goto :fail

echo Derleniyor (birkac dakika surer)...
"%VPY%" -m PyInstaller --noconfirm --clean --windowed --name "FK JARVIS" ^
  --collect-submodules uvicorn --collect-submodules app ^
  --collect-data speech_recognition --collect-data googleapiclient ^
  --hidden-import pyttsx3.drivers --hidden-import pyttsx3.drivers.sapi5 ^
  --hidden-import pynput.keyboard._win32 --hidden-import pynput.mouse._win32 ^
  --hidden-import win32com.client --hidden-import pythoncom ^
  jarvis.pyw
if errorlevel 1 goto :fail

rem Ayar sablonlari .exe'nin yanina kopyalanir; .env ve data\ KOPYALANMAZ (gizli bilgiler).
if not exist "dist\FK JARVIS\config" mkdir "dist\FK JARVIS\config"
copy /y ".env.example" "dist\FK JARVIS\.env.example" >nul
copy /y "config\apps.example.json" "dist\FK JARVIS\config\apps.example.json" >nul
if not exist "dist\FK JARVIS\.env" copy /y ".env.example" "dist\FK JARVIS\.env" >nul

echo.
echo [OK] Hazir: dist\FK JARVIS\FK JARVIS.exe
echo Klasorun tamamini (dist\FK JARVIS) baska bilgisayara kopyalayabilirsiniz.
echo API anahtarlarini uygulamadaki Ayarlar sekmesinden girin.
pause
exit /b 0

:fail
echo [HATA] Derleme basarisiz. Yukaridaki mesaji kontrol edin.
pause
exit /b 1
