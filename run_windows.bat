@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title FK JARVIS

if not exist ".venv\Scripts\python.exe" (
  echo Kurulum bulunamadi, once setup_windows.bat calistiriliyor...
  call "%~dp0setup_windows.bat"
  if not exist ".venv\Scripts\python.exe" exit /b 1
)

set PYTHONUTF8=1
echo FK JARVIS baslatiliyor... (bu pencereyi kapatirsaniz uygulama da kapanir)
".venv\Scripts\python.exe" -m app.main
if errorlevel 1 (
  echo.
  echo [HATA] FK JARVIS beklenmedik sekilde kapandi. Ayrintilar: jarvis.log
  echo Kontrol icin: .venv\Scripts\python.exe -m app.doctor
  pause
)
