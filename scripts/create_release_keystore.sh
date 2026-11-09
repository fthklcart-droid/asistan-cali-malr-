#!/usr/bin/env bash
# SRİUS JARVIS üretim (release) imza anahtarı oluşturur. Bu dosyayı ve şifreleri ASLA depoya koyma.
set -euo pipefail
OUT="${1:-signing/release.jks}"
ALIAS="${2:-sirius-release}"
mkdir -p "$(dirname "$OUT")"
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000
cat <<MSG

Oluşturuldu: $OUT
Şimdi proje kökünde keystore.properties dosyası oluştur (git'e eklenmez):

storeFile=$OUT
storePassword=<anahtar deposu şifren>
keyAlias=$ALIAS
keyPassword=<anahtar şifren>

Ardından:  ./gradlew assembleRelease   ->  app/build/outputs/apk/release/app-release.apk
MSG
