# FK JARVIS Mobil — Android 15 / 16

Kotlin + Jetpack Compose. `minSdk 35` (Android 15), `targetSdk 36` (Android 16).

## Ne yapar (0.3.0)

| Sekme | Özellik |
|---|---|
| **Genel** | Holographic Core, Claude durumu, bugünkü istek sayısı ve tahmini maliyet, açık görevler, hızlı soru |
| **Sohbet** | Claude ile Türkçe sohbet; internette arama ve sayfa okuma (Claude sunucu araçları); 🎙 sesli soru (Android konuşma tanıma, tr-TR); 🔊 yanıtları sesli okuma (Android metinden sese) |
| **Görevler** | Yapılacaklar listesi; hatırlatma (15 dk / 1 sa / 3 sa / yarın 09:00 / saat seç) → bildirim; telefon yeniden başlayınca hatırlatmalar yeniden kurulur |
| **Ayarlar** | Claude API anahtarı, model (Opus 5.5 / Sonnet 5.5 / Haiku 5.5), düşünme derinliği, sesli yanıt, sohbet geçmişini silme |

## Güvenlik ve gizlilik

- API anahtarı **APK içinde değildir**. Kullanıcı uygulamada girer; Android Keystore'daki donanım destekli AES-GCM
  anahtarıyla şifrelenip yalnız bu telefonda saklanır. Uygulama yedeklemesi kapalıdır (`allowBackup=false`).
- Sohbet geçmişi ve görevler yalnız uygulamanın özel klasöründedir. Mesajlar yanıt için yalnız `api.anthropic.com`'a gider.
- Yalnız HTTPS (`usesCleartextTraffic=false`). İzinler: İnternet, bildirim, açılışta hatırlatmaları yeniden kurma.
  Mikrofon izni istenmez: sesli giriş Android'in kendi konuşma tanıma ekranıyla yapılır.

## Henüz yok: bilgisayarla eşleştirme

Masaüstü FK JARVIS köprüsü güvenlik gereği yalnız o bilgisayardan (127.0.0.1) erişilebilir. Telefondan görev gönderme
ve onay verme için mimari belgede öngörülen **giden bağlantılı (outbound TLS) relay** gerekir; bkz.
`docs/YOL_HARITASI.md`. Bu sürüm bu yüzden telefonda bağımsız çalışır.

## APK'yı kurma

1. GitHub › Actions › **Android APK** › son başarılı çalıştırma › Artifacts › `FK_JARVIS_Android_APK` indirin.
2. ZIP'ten çıkan `FK_JARVIS_<sürüm>.apk` dosyasını telefona aktarıp açın. "Bilinmeyen uygulamalar" izni istenirse
   o kaynak (ör. Dosyalar) için izin verin.
3. Uygulamada **Ayarlar › Claude API anahtarı** girin (https://console.anthropic.com).

## İmza

APK, depodaki **test anahtarı** (`app/fkjarvis-test.jks`, şifre `fkjarvis-test`) ile imzalanır; böylece yeni sürümler
eskisinin üzerine kurulabilir. Bu anahtar herkese açıktır: **Play Store için** gizli bir üretim anahtarı oluşturup
`FKJ_KEYSTORE`, `FKJ_STORE_PASSWORD`, `FKJ_KEY_ALIAS`, `FKJ_KEY_PASSWORD` ortam değişkenleriyle (CI'da GitHub
Secrets) verin.

## Derleme

- CI: `.github/workflows/android.yml` (Ubuntu, JDK 17, Android SDK 36, Gradle 8.14): birim testleri, imzalı
  `assembleRelease`, `apksigner verify`, `aapt` ile SDK sürümü denetimi.
- Yerel: Android Studio ile bu klasörü açın (SDK 36, JDK 17) › Build › Build APK(s), veya `gradle assembleRelease`.

## Dosyalar

| Dosya | Görev |
|---|---|
| `MainActivity.kt` | Compose arayüzü (4 sekme), konuşma tanıma ve metinden sese |
| `ClaudeClient.kt` | Claude Messages API (ham HTTPS, `anthropic-version: 2026-10-21`), yeniden deneme, Türkçe hatalar |
| `SecureStore.kt` | Keystore ile şifreli anahtar saklama |
| `Store.kt` | Sohbet, görevler, ayarlar, günlük kullanım |
| `Reminders.kt` | AlarmManager + bildirim + açılışta yeniden kurma |
| `Pricing.kt` | Model fiyatları ve maliyet tahmini (birim testli) |
