# FK JARVIS — TEK PAKET (Windows + Android 15/16)

Bu ZIP, Windows masaüstü başlangıç projesini, Türkçe Windows sesli komut modülünü, Android 15/16 companion uygulama kaynaklarını ve Claude Code geliştirme yönergelerini tek yerde toplar.

## 1) Windows kurulumu
1. Windows 10/11 kullanın. Python yoksa kurulum betiği winget ile Python 3.12 kurmayı teklif eder.
2. `setup_windows.bat` dosyasını çalıştırın (ayrıntılar: `README.md`).
3. `.env` dosyasına Anthropic API anahtarınızı ekleyin. Gemini sesli görüşme için Google AI Studio API anahtarı ve hesabınızda etkin bir Live model kimliği girin.
4. Photoshop, Premiere, Lightroom, CapCut ve tarayıcı otomatik aranır; bulunamayanların yolunu `config/apps.json` dosyasına yazın.
5. `run_windows.bat` dosyasını çalıştırın.

### Windows sesli komut
- Panelde **Windows sesli komut (4 sn)** düğmesine basın ve kısa Türkçe komut söyleyin: “Photoshop'u aç”, “Premiere'i aç”, “Lightroom'u aç”, “CapCut'ı aç” veya “Chrome'u aç”.
- Ses tanıma internet üzerinden Google Web Speech servisiyle yapılır; mikrofon ve internet gerekir.
- Uygulama doğrudan açılmaz: güvenlik için onay isteği oluşturulur. **Seçileni onayla** ile açılır.
- Gemini Live düğmesi ayrı, doğal sesli görüşme modülüdür; Windows uygulama komutları için kısa komut düğmesini kullanın.

## 2) Android 15/16
`android/FK_JARVIS_Android_15_16` klasörünü Android Studio ile açın. Android kaynak projesidir; imzalı APK değildir. Henüz backend eşleştirme, gerçek Claude sohbeti, Gemini Live oturumu, bildirim ve uzaktan görev yürütme bağlantıları tamamlanmış değildir. Gradle senkronizasyonu ve Android Studio ile derleme gerekir.

## 3) Gerçek entegrasyon durumu
- Windows masaüstü arayüzü, yerel köprü, Claude görev planlama bağlantı iskeleti ve uygulama başlatma onay akışı bulunur.
- Windows kısa sesli komut tanıma ve allow-list uygulama açma onayı bu pakete eklendi.
- Gemini Live ses görüşmesi modülü bulunur; model adı ve API erişimi Google hesabınızda etkin modele göre yapılandırılmalıdır.
- Android tarafı arayüz/kaynak başlangıcıdır; canlı servislerle bağlı değildir.
- Photoshop/Premiere/CapCut içinde otomatik düzenleme, tam Claude Computer Use döngüsü, WhatsApp Business, YouTube yayınlama ve Meta reklam yönetimi bu ZIP'te tamamlanmış üretim entegrasyonları değildir.

## 4) Güvenlik
API anahtarlarını `.env` içinde tutun, ZIP'i paylaşırken `.env` dosyasını eklemeyin. Mesaj gönderme, yayınlama, reklam bütçesi değiştirme, satın alma, silme ve form gönderme gibi dış etkili işlemler ayrıca açık onay gerektirmelidir.

## 5) Claude Code
`docs/CLAUDE_CODE_BRIEF_TR.md` ve `android/FK_JARVIS_Android_15_16/CLAUDE_CODE_PROMPT_TR.md` dosyalarını Claude Code'a vererek eksik entegrasyonları aşamalı tamamlatabilirsiniz.
