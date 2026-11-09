#!/usr/bin/env bash
# Emülatörde duman testi: kur, aç, gizlilik ekranını kabul et, tüm bölmeleri gez, ekran görüntüsü al,
# çökme (FATAL EXCEPTION / ANR) olursa başarısız ol.
set -u
APK="$1"
OUT="${2:-smoke}"
PKG=com.fk.jarvis
mkdir -p "$OUT"
log() { echo "[smoke] $*"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png"; log "ekran görüntüsü: $1"; }

# Ekranda yazısı $1 olan öğeyi bulup dokunur. Bulamazsa alt menüyü kaydırıp tekrar dener.
tap_text() {
  local want="$1"
  local exact="${2:-}"
  for attempt in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    local xy
    xy=$(python3 - "$want" "$OUT/ui.xml" "$exact" <<'PY'
import re, sys
want, path, exact = sys.argv[1], sys.argv[2], sys.argv[3] == "exact"
try:
    xml = open(path, encoding="utf-8").read()
except Exception:
    sys.exit(0)
nodes = [(m.group(1), tuple(map(int, m.group(2, 3, 4, 5))))
         for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]
# Önce birebir eşleşme, sonra içeren; görünür alanı olan son düğüm (en üstteki katman) tercih edilir.
for pick in ([n for n in nodes if n[0] == want], [] if exact else [n for n in nodes if want in n[0]]):
    pick = [n for n in pick if n[1][2] > n[1][0] and n[1][3] > n[1][1]]
    if pick:
        x1, y1, x2, y2 = pick[-1][1]
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -n "$xy" ]; then adb shell input tap $xy; log "dokunuldu: $want ($xy)"; return 0; fi
    # alt menüyü sola kaydır (ekranın en altı)
    local size w h
    size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); w=${size%x*}; h=${size#*x}
    adb shell input swipe $((w * 85 / 100)) $((h - 120)) $((w * 15 / 100)) $((h - 120)) 300
    sleep 1
  done
  log "BULUNAMADI: $want"
  return 1
}

FAIL=0
log "kurulum"
adb install -r -g "$APK" || { log "KURULUM BAŞARISIZ"; exit 1; }
adb shell dumpsys package $PKG | grep -E "versionName|targetSdk" | head -3
adb logcat -c

log "açılış"
adb shell am start -W -n $PKG/.MainActivity
sleep 8
shot 01_gizlilik
tap_text "KABUL EDİYORUM" || FAIL=1
sleep 9   # avatar açılış animasyonu
shot 02_asistan

i=3
for TAB in "SOHBET" "UYGULAMA" "ARAYÜZ" "SUNUCU" "SİSTEM" "AYARLAR"; do
  if tap_text "$TAB" exact; then sleep 3; shot "$(printf '%02d' $i)_$TAB"; else FAIL=1; fi
  i=$((i + 1))
done

# SUNUCU'da Gemini kartı ve Live paneli; ardından asistana dön, mikrofon küresine dokun
tap_text "SUNUCU" exact && sleep 2 && tap_text "GEMINI" exact && sleep 2
adb shell input swipe 500 1700 500 300 400; sleep 1; adb shell input swipe 500 1700 500 300 400; sleep 2; shot "${i}_SUNUCU_live"; i=$((i + 1))
tap_text "ARAYÜZ" exact && sleep 2 && tap_text "NEON" exact && sleep 6 && shot "${i}_neon"; i=$((i + 1))
tap_text "ART" exact && sleep 2
tap_text "ASİSTAN" exact && sleep 2
tap_text "Komut yaz" && sleep 1 && adb shell input text "saat%skac" && adb shell input keyevent KEYCODE_ENTER && sleep 5 && shot "${i}_komut"; i=$((i + 1))
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "KONUŞ" exact && sleep 5 && shot "${i}_konus"; i=$((i + 1))
adb shell input keyevent KEYCODE_BACK; sleep 2

log "çökme denetimi"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A30 "$PKG" > "$OUT/crash.txt" || true
grep -n "ANR in $PKG" "$OUT/logcat.txt" >> "$OUT/crash.txt" || true
if [ -s "$OUT/crash.txt" ]; then
  log "ÇÖKME BULUNDU:"; cat "$OUT/crash.txt"; FAIL=1
else
  log "çökme yok"
fi
if adb shell pidof $PKG >/dev/null; then log "uygulama çalışıyor"; else log "uygulama süreci yok"; fi
# Uygulamanın kendi hata/uyarı satırları (bilgi amaçlı)
grep -E " (E|W) .*(jarvis|AndroidRuntime|chromium)" "$OUT/logcat.txt" | tail -60 > "$OUT/warnings.txt" || true
exit $FAIL
