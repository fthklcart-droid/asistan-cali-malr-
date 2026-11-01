@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title FK JARVIS Kurulum

echo ============================================
echo   FK JARVIS - Windows 11 kurulumu
echo ============================================
echo.

rem --- 1) Uygun Python surumunu bul (3.12 > 3.13 > 3.11) ---
set "PY="
for %%V in (3.12 3.13 3.11) do (
  if not defined PY (
    py -%%V -c "import sys" >nul 2>&1 && set "PY=py -%%V"
  )
)
if not defined PY (
  python -c "import sys; sys.exit(0 if (3,10) <= sys.version_info[:2] < (3,14) else 1)" >nul 2>&1 && set "PY=python"
)
if not defined PY goto :no_python
echo [OK] Python bulundu: %PY%

rem --- 2) Sanal ortam ---
if not exist ".venv\Scripts\python.exe" (
  echo Sanal ortam olusturuluyor...
  %PY% -m venv .venv
  if errorlevel 1 goto :fail
)
set "VPY=.venv\Scripts\python.exe"

rem --- 3) Paketler ---
echo Paketler yukleniyor (ilk seferde birkac dakika surebilir)...
"%VPY%" -m pip install --upgrade pip --disable-pip-version-check -q
"%VPY%" -m pip install -r requirements.txt --disable-pip-version-check
if errorlevel 1 goto :fail

rem --- 4) Ayar dosyalari ---
if not exist ".env" copy ".env.example" ".env" >nul && echo [OK] .env olusturuldu
if not exist "config\apps.json" copy "config\apps.example.json" "config\apps.json" >nul && echo [OK] config\apps.json olusturuldu

rem --- 5) Masaustu kisayolu ---
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'FK JARVIS.lnk')); $s.TargetPath=(Resolve-Path '.venv\Scripts\pythonw.exe').Path; $s.Arguments='-m app.main'; $s.WorkingDirectory=(Get-Location).Path; $s.Description='FK JARVIS'; $s.Save()" >nul 2>&1 && echo [OK] Masaustune "FK JARVIS" kisayolu eklendi

rem --- 6) Kontrol ---
echo.
set PYTHONUTF8=1
"%VPY%" -m app.doctor
echo.
echo Kurulum tamamlandi.
echo  - API anahtarlarini girmek icin .env dosyasini Not Defteri ile acin.
echo  - Uygulama yollari otomatik bulunamadiysa config\apps.json dosyasini duzenleyin.
echo  - Baslatmak icin run_windows.bat veya masaustundeki "FK JARVIS" kisayolunu kullanin.
echo.
set /p OPEN_ENV=".env dosyasi simdi acilsin mi? (E/H): "
if /i "%OPEN_ENV%"=="E" start "" notepad ".env"
pause
exit /b 0

:no_python
echo [HATA] Python 3.11 / 3.12 / 3.13 bulunamadi.
echo.
where winget >nul 2>&1
if errorlevel 1 goto :manual_python
choice /c EH /m "Python 3.12 winget ile simdi kurulsun mu"
if errorlevel 2 goto :manual_python
winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
echo.
echo Python kuruldu. Bu pencereyi kapatip setup_windows.bat dosyasini YENIDEN calistirin.
pause
exit /b 1

:manual_python
echo https://www.python.org/downloads/ adresinden Python 3.12 indirip kurun.
echo Kurulumda "Add python.exe to PATH" kutusunu isaretleyin, sonra bu dosyayi tekrar calistirin.
pause
exit /b 1

:fail
echo.
echo [HATA] Kurulum basarisiz oldu. Yukaridaki hata mesajini kontrol edin.
echo  - Internet baglantinizi kontrol edin.
echo  - Klasoru silip yeniden deneyebilirsiniz: .venv
pause
exit /b 1
