@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title FK JARVIS EXE derleme
if not exist ".venv\Scripts\python.exe" (
  echo Once setup_windows.bat calistirin.
  if not defined CI pause
  exit /b 1
)
set "VPY=.venv\Scripts\python.exe"
echo PyInstaller kuruluyor...
"%VPY%" -m pip install --disable-pip-version-check -q "pyinstaller>=6.10"
if errorlevel 1 goto :fail

set "INST="
if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" set "INST=--installer"
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set "INST=--installer"
echo Derleniyor (birkac dakika surer)...
"%VPY%" packaging\build_windows.py %INST%
if errorlevel 1 goto :fail

echo.
echo Paket kontrol ediliyor (kendi kendini sinama)...
rem Pencereli (GUI) .exe icin cmd beklemez; start /wait cikis kodunu da dondurur
start "" /wait "dist\FK JARVIS\FK JARVIS.exe" --selftest "%TEMP%\fk_jarvis_selftest.json"
if errorlevel 1 (
  echo [HATA] Kendi kendini sinama basarisiz. Rapor: %TEMP%\fk_jarvis_selftest.json
  goto :fail
)
echo [OK] Kendi kendini sinama basarili.
echo.
echo Program klasoru: dist\FK JARVIS\FK JARVIS.exe
if defined INST (echo Kurulum dosyasi: dist\installer\) else (echo Kurulum dosyasi icin Inno Setup 6 kurun: winget install JRSoftware.InnoSetup)
if not defined CI pause
exit /b 0

:fail
echo [HATA] Derleme basarisiz. Yukaridaki mesaji kontrol edin.
if not defined CI pause
exit /b 1
