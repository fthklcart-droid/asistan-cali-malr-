#!/usr/bin/env bash
# Emülatörde duman testi: kur, aç, gizlilik ekranını kabul et, tüm bölmeleri gez, ekran görüntüsü al,
# çökme (FATAL EXCEPTION / ANR) olursa başarısız ol.
set -u
APK="$1"
OUT="${2:-smoke}"
PKG=com.fk.jarvis
mkdir -p "$OUT"
log() { echo "[smoke] $*"; }

shot() { timeout 25 adb exec-out screencap -p > "$OUT/$1.png"; log "ekran görüntüsü: $1"; }

# Ekranda yazısı $1 olan öğeyi bulup dokunur. Bulamazsa alt menüyü kaydırıp tekrar dener.
tap_text() {
  local want="$1"
  local exact="${2:-}"
  for attempt in 1 2 3 4; do
    dismiss_system_dialogs
    timeout 25 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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

# Emülatörün kendi uygulamalarından gelen "... yanıt vermiyor" pencerelerini kapatır (bizim uygulamamızla ilgisizdir).
dismiss_system_dialogs() {
  timeout 25 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  if grep -qE "isn't responding|yanıt vermiyor" "$OUT/ui.xml" 2>/dev/null; then
    local who
    who=$(grep -oE "[A-Za-z ]+ isn't responding" "$OUT/ui.xml" | head -1)
    log "sistem penceresi kapatılıyor: $who"
    case "$who" in *JARVIS*|*SRİUS*) log "UYARI: bizim uygulama yanıt vermiyor"; ANR_OURS=1 ;; esac
    local xy
    xy=$(python3 -c "
import re,sys
x=open('$OUT/ui.xml',encoding='utf-8').read()
m=re.search(r'text=\"Wait\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]', x)
print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2) if m else None" 2>/dev/null)
    if [ -n "$xy" ] && [ "$xy" != "None" ]; then adb shell input tap $xy; else adb shell input keyevent KEYCODE_BACK; fi
    sleep 2
  fi
}

ANR_OURS=0
FAIL=0
log "emülatör hazırlığı"
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent KEYCODE_HOME
sleep 15   # ana ekran uygulaması yerleşsin
dismiss_system_dialogs
log "kurulum"
adb install -r -g "$APK" || { log "KURULUM BAŞARISIZ"; exit 1; }
adb shell dumpsys package $PKG | grep -E "versionName|targetSdk" | head -3
adb logcat -c

log "açılış"
timeout 60 adb shell am start -W -n $PKG/.MainActivity
sleep 8
shot 01_gizlilik
tap_text "KABUL EDİYORUM" || FAIL=1
sleep 9   # avatar açılış animasyonu
shot 02_asistan

i=3
# Alt çubuktaki bölmeler
for TAB in "MERKEZ" "PLAN" "AJAN" "TAKVİM" "WEB" "MENÜ"; do
  if tap_text "$TAB" exact; then sleep 4; shot "$(printf '%02d' $i)_$TAB"; else FAIL=1; fi
  i=$((i + 1))
done
# MENÜ'den açılan bölümler
for SUB in "UYGULAMA" "SOHBET" "HAFIZA" "ARAYÜZ" "SUNUCU" "SİSTEM" "AYARLAR"; do
  if tap_text "MENÜ" exact && sleep 2 && tap_text "$SUB" exact; then sleep 3; shot "$(printf '%02d' $i)_$SUB"; else FAIL=1; fi
  i=$((i + 1))
done

# SUNUCU'da Gemini kartı ve Live paneli; ardından asistana dön, mikrofon küresine dokun
tap_text "MENÜ" exact && sleep 2 && tap_text "SUNUCU" exact && sleep 2 && tap_text "GEMINI" exact && sleep 2
adb shell input swipe 500 1700 500 300 400; sleep 1; adb shell input swipe 500 1700 500 300 400; sleep 2; shot "${i}_SUNUCU_live"; i=$((i + 1))
tap_text "ASİSTAN" exact && sleep 2
tap_text "Komut yaz" && sleep 1 && adb shell input text "saat%skac" && adb shell input keyevent KEYCODE_ENTER && sleep 5 && shot "${i}_komut"; i=$((i + 1))
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "KONUŞ" exact && sleep 5 && shot "${i}_konus"; i=$((i + 1))
adb shell input keyevent KEYCODE_BACK; sleep 2

# ---------------------------------------------------------------------------------------------
# İŞLEV TESTLERİ: komutlar uygulamanın yazılı komut yoluna gönderilir (DEBUG derlemedeki test kancası),
# yanıtın ekranda göründüğü doğrulanır. Yapay zekâ gerektirmeyen, cihaz içinde çalışan özellikleri sınar.
# Sonuçlar functional.txt dosyasına yazılır; biri bile kalırsa test başarısız olur.
# ---------------------------------------------------------------------------------------------
RES="$OUT/functional.txt"; : > "$RES"
dump_ui() { timeout 25 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
# $1: komut (tek tırnak içermemeli)  $2: ekranda beklenen metin (genişletilmiş düzenli ifade)
say_expect() {
  # Türkçe karakterler adb kabuğunda bozulmasın diye komut base64 ile taşınır.
  adb shell "am start -n $PKG/.MainActivity --es jarvis_test_command_b64 '$(printf '%s' "$1" | base64 -w0)'" >/dev/null 2>&1
  local ok=0
  for t in 1 2 3 4 5 6 7; do
    sleep 2; dump_ui
    if grep -qE "$2" "$OUT/ui.xml" 2>/dev/null; then ok=1; break; fi
  done
  if [ $ok = 1 ]; then echo "GEÇTİ | $1  →  $2" >> "$RES"; log "işlev GEÇTİ: $1"
  else echo "KALDI | $1  →  beklenen: $2" >> "$RES"; log "işlev KALDI: $1 (beklenen: $2)"; FAIL=1; shot "kaldi_$(echo "$1" | tr -c 'a-zA-Z0-9' '_' | cut -c1-30)"; fi
}
log "işlev testleri"
tap_text "ASİSTAN" exact && sleep 2
say_expect "saat kaç" "Saat [0-9]{2}:[0-9]{2}"
say_expect "görev ekle: süt al" "Görevlerine ekledim: süt al"
say_expect "görev ekle: yarın raporu gönder" "Görevlerine ekledim: raporu gönder \\(yarın\\)"
say_expect "görevlerim neler" "Açık görevlerin \\(2\\)"
say_expect "süt al görevini tamamla" "tamamlandı olarak işaretledim"
say_expect "geri al" "görevini yeniden açtım"
say_expect "not al emülatör testi notu" "Not aldım"
say_expect "bunu hatırla: en sevdiğim renk lacivert" "hatırlayacağım: en sevdiğim renk lacivert"
say_expect "benim hakkımda ne biliyorsun" "Hatırladıklarım: en sevdiğim renk lacivert"
say_expect "pil durumu" "Pil yüzde [0-9]+"
say_expect "neler yapabilirsin" "Şu an yapabildiklerim"
say_expect "yarın neler var" "Takviminde yarın"
say_expect "uzay haberlerini takip et" "konusunu takibe aldım"
say_expect "kotlin diye ara" "tarayıcıda açtım"
tap_text "ASİSTAN" exact && sleep 2
# Onay akışı: hafızayı silmek onay ister; ONAYLA'ya basılınca silinir.
say_expect "hafızanı sil" "tüm bilgileri sileceğim"
if tap_text "ONAYLA" exact; then
  sleep 3; dump_ui
  if grep -qE "bilgiyi hafızamdan sildim" "$OUT/ui.xml"; then echo "GEÇTİ | onay → hafıza silindi" >> "$RES"; else echo "KALDI | onay → hafıza silinmedi" >> "$RES"; FAIL=1; fi
else echo "KALDI | ONAYLA düğmesi bulunamadı" >> "$RES"; FAIL=1; fi
shot "${i}_islev"; i=$((i + 1))

# Hatırlatıcı: ~2 dakika sonrasına kur, uygulamayı arka plana al, bildirimin geldiğini doğrula.
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true
NOW=$(adb shell date +%s | tr -d '\r'); AT=$((NOW + 130)); HHMM=$(adb shell date -d "@$AT" +%H:%M | tr -d '\r')
log "hatırlatıcı $HHMM için kuruluyor (cihaz saati: $(adb shell date +%H:%M:%S | tr -d '\r'))"
say_expect "$HHMM için emülatör testini hatırlat" "Hatırlatıcı kuruldu"
tap_text "PLAN" exact && sleep 3 && shot "${i}_plan_dolu"; i=$((i + 1))
adb shell input keyevent KEYCODE_HOME
GOT=0
for t in $(seq 1 40); do
  sleep 5
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "Hatırlatma: "; then GOT=1; break; fi
done
LATE=$(( $(adb shell date +%s | tr -d '\r') - (AT - AT % 60) ))
if [ $GOT = 1 ]; then echo "GEÇTİ | hatırlatıcı bildirimi geldi (uygulama arka plandayken; hedef dakikanın başına göre ${LATE} sn)" >> "$RES"; log "hatırlatıcı bildirimi geldi"
else echo "KALDI | hatırlatıcı bildirimi 200 sn içinde gelmedi" >> "$RES"; log "hatırlatıcı bildirimi GELMEDİ"; FAIL=1; fi
adb shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2; shot "${i}_bildirim"; i=$((i + 1))
adb shell cmd statusbar collapse >/dev/null 2>&1
timeout 60 adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
log "işlev sonuçları:"; cat "$RES"

log "çökme denetimi"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A30 "$PKG" > "$OUT/crash.txt" || true
grep -n "ANR in $PKG" "$OUT/logcat.txt" >> "$OUT/crash.txt" || true
if [ -s "$OUT/crash.txt" ]; then
  log "ÇÖKME BULUNDU:"; cat "$OUT/crash.txt"; FAIL=1
else
  log "çökme yok"
fi
[ "$ANR_OURS" = 1 ] && FAIL=1

# NEON en sonda ve bilgi amaçlı: emülatörün yazılımla çizimi (GPU yok) bu avatarda ekranı dondurabiliyor.
# Sonuç testi başarısız saymaz; gerçek cihazda ayrıca denenmelidir.
log "NEON (bilgi amaçlı, süre sınırlı)"
( tap_text "MENÜ" exact && sleep 2 && tap_text "ARAYÜZ" exact && sleep 2 && tap_text "NEON" exact && sleep 15 && shot "${i}_neon" ) & NP=$!
( sleep 120; kill $NP 2>/dev/null ) & KP=$!
wait $NP 2>/dev/null && log "NEON ekran görüntüsü alındı" || log "NEON: emülatörde doğrulanamadı (zaman aşımı)"
kill $KP 2>/dev/null || true
if timeout 20 adb shell pidof $PKG >/dev/null; then log "uygulama çalışıyor"; else log "uygulama süreci yok"; fi
# Uygulamanın kendi hata/uyarı satırları (bilgi amaçlı)
grep -E " (E|W) .*(jarvis|AndroidRuntime|chromium)" "$OUT/logcat.txt" | tail -60 > "$OUT/warnings.txt" || true
exit $FAIL
