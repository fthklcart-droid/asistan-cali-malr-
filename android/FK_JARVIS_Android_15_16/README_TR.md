# FK JARVIS Android – Mobil Başlangıç Projesi

Android Studio projesi; Android 15 (API 35) ve Android 16 (API 36) için yapılandırılmıştır.

## Önemli mimari kararı
- Android uygulaması yalnızca istemcidir.
- Claude ve Gemini API anahtarları APK içine kesinlikle konulmaz.
- Mobil uygulama, güvenli HTTPS/WSS üzerinden FK JARVIS backend'ine bağlanır.
- Backend, Claude görev planlamasını ve Gemini Live ses oturumlarını yürütür.
- Masaüstü ajanı yalnızca eşleştirilmiş ve çevrimiçi olduğunda bilgisayarda işlem yapar.
- WhatsApp gönderimi, YouTube yayını, form gönderimi, dosya silme ve harcama gibi işlemler onay gerektirir.

## Geliştirme ortamı
- Android Studio güncel sürüm
- JDK 17
- Android SDK Platform 36
- Gradle wrapper'ı Android Studio üzerinden oluşturun veya projeyi Android Studio ile açıp Gradle Sync çalıştırın.

## Derleme
Android Studio'da klasörü açın, SDK 36'yı yükleyin ve Build > Build APK(s) seçin.

## Şu anki kapsam
Bu paket çalışan bir Android Compose arayüz iskeletidir. Görev gönderme, Claude sohbeti, Gemini Live sesli konuşma, push bildirimleri, masaüstü eşleştirme ve uzaktan onaylar için backend API henüz bağlanmamıştır. Ekrandaki gönderim butonu demo/taslak davranışı gösterir; gerçek görev yürütmez.

## Sonraki entegrasyon sözleşmesi
Backend uç noktaları önerisi:
- POST /v1/mobile/pair
- POST /v1/tasks
- GET /v1/tasks
- GET /v1/tasks/{id}
- POST /v1/tasks/{id}/approve
- POST /v1/tasks/{id}/reject
- POST /v1/voice/sessions (Gemini Live ephemeral session)
- WebSocket /v1/mobile/events

Kimlik doğrulama: kısa ömürlü access token + refresh token, cihaz eşleştirme, TLS, rate limit ve audit log.
