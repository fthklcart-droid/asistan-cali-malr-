# FK JARVIS — Android Sesli Asistan

Türkçe sesli komutları anlayan, Gemini ile sohbet edebilen, telefondaki uygulamaları güvenli biçimde açıp kontrol edebilen Android asistanı. İki farklı animasyonlu arayüzü vardır:

| Arayüz | Görünüm |
|---|---|
| **NEON** | Parçacık küresinden galaksiye, oradan mor/altın neon bir figüre dönüşen açılış; uzun ışık saçları, yıldırımlar, duman. |
| **ART** | Alttaki ışık noktasından akan parçacıklarla "ASSEMBLING… %" montajı; turkuaz kontur çizgili büst, turuncu parlayan yüz, parçacıklı dağlar ve altın damarlar. |

Arayüzler alt çubuktaki **ARAYÜZ: NEON / ART** düğmesiyle ya da Ayarlar > Arayüz bölümünden değiştirilir. İkisi de aynı asistan durumlarına (bekleme, dinleme, işleme, konuşma, yürütme, onay, hata) tepki verir.

---

## 1. Mimari raporu

Depo başlangıçta boştu (yalnızca README). Şartnamede "mevcut avatar" olarak geçen görsel katman, paylaşılan iki referans videoya göre bu projede yazıldı (`app/src/main/assets/avatar/`). Asistan altyapısı bu avatarın gerçek JS API'sine bağlandı.

```
app/src/main/
├── assets/avatar/          index.html + avatar.js (NEON) + art.js (ART) — Canvas 2D, WebView içinde
└── java/com/fk/jarvis/
    ├── JarvisApp.kt                    Bileşenlerin kurulumu (Activity ve erişilebilirlik hizmeti aynı orkestratörü paylaşır)
    ├── MainActivity.kt                 Compose arayüz + mikrofon izni
    ├── core/
    │   ├── AssistantOrchestrator.kt    İstek akışı ve durum makinesi (IDLE/LISTENING/PROCESSING/SPEAKING/EXECUTING/CONFIRMING/ERROR)
    │   ├── IntentClassifier.kt         İnternetsiz, kural tabanlı Türkçe komut sınıflandırıcı
    │   ├── SafetyPolicy.kt             İzin listesi, argüman doğrulama, onay kuralları
    │   ├── CommandRouter.kt            Doğrulanmış komutu Android eylemine yönlendirme
    │   ├── CommandProposal.kt          Yapılandırılmış komut şeması (intent/target/arguments/requires_confirmation/spoken_response)
    │   └── Ports.kt                    Değiştirilebilir arayüzler (SpeechInput, SpeechOutput, HistoryStore, CommandExecutor)
    ├── llm/        LLMProvider (arayüz), GeminiProvider, AssistantPrompt (sistem talimatı + JSON şeması)
    ├── voice/      SpeechToTextProvider (arayüz), AndroidSpeechToText, VoiceInputManager, TextToSpeechManager
    ├── apps/       AndroidAppController (Seviye A intent'ler), AppMatcher (cihazdaki uygulama listesiyle eşleştirme)
    ├── accessibility/  JarvisAccessibilityService + AccessibilityActionProvider (Seviye B, kullanıcı onaylı)
    ├── permissions/    PermissionManager
    ├── avatar/         AvatarBridge (asistan durumu → avatar JS API'si)
    ├── data/           SecureKeyStore (Keystore ile şifreli API anahtarı), SettingsRepository, ConversationRepository
    └── ui/             JarvisScreen, SettingsPanel, Theme
```

Şartnamedeki modül adlarının karşılıkları: `VoiceInputManager`, `SpeechToTextProvider`, `AssistantOrchestrator`, `LLMProvider`/`GeminiProvider`, `IntentClassifier`, `CommandRouter`, `AndroidAppController`, `AccessibilityActionProvider`, `TextToSpeechManager`, `PermissionManager`, `AvatarBridge`, `ConversationRepository` — hepsi yukarıdaki dosyalardadır.

### İstek akışı

1. **KONUŞ** düğmesi (ya da erişilebilirlik hizmeti açıksa diğer uygulamaların üzerindeki küçük mikrofon düğmesi) → mikrofon izni kontrol edilir.
2. Android `SpeechRecognizer` ile `tr-TR` konuşma tanıma (kısmi sonuçlar ekranda canlı görünür).
3. `IntentClassifier` cihaz içinde komutu tanımaya çalışır. Tanırsa internet gerekmez.
4. Tanıyamazsa ve Gemini ayarlıysa metin Gemini'ye gönderilir; model **yalnızca JSON şemasına uygun bir öneri** döndürür.
5. `SafetyPolicy` öneriyi doğrular: izin listesinde olmayan eylem reddedilir, argümanlar kontrol edilir, sonuç doğuran işlemler onaya bağlanır. Model onay gereksinimini kaldıramaz, yalnızca ekleyebilir.
6. Gerekirse kullanıcıdan onay istenir (ekranda **ONAYLA / İPTAL** ya da sesli "evet / hayır").
7. `CommandRouter` eylemi gerçekleştirir. Yanıt metni, eylemin **gerçek sonucundan** üretilir (ör. uygulama bulunamadıysa "açtım" denmez).
8. `TextToSpeechManager` yanıtı Türkçe seslendirir. Her aşama `AvatarBridge` ile avatara bildirilir.

---

## 2. APK'yı alma ve kurma

### Hazır APK (GitHub Actions)
Her push'ta `.github/workflows/android.yml` birim testlerini çalıştırır ve debug APK üretir:
1. GitHub'da depo → **Actions** → son "Android build" çalışması.
2. **Artifacts** bölümünden `FK-JARVIS-debug-apk` dosyasını indir, zip'ten `app-debug.apk`'yı çıkar.
3. Telefona aktar, aç. "Bilinmeyen kaynaklardan yükleme" iznini istenirse ver.

### Kendin derlemek
Android Studio (Ladybug veya üstü) ya da Android SDK + JDK 17:
```bash
./gradlew testDebugUnitTest   # testler
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
`minSdk 26`, `targetSdk/compileSdk 35`. Android 15 ve 16 hedeflenmiştir.

---

## 3. İzinler

| İzin | Neden | Ne zaman |
|---|---|---|
| `RECORD_AUDIO` | Sesli komut | İlk kez KONUŞ'a basınca sorulur. Reddedilirse uygulama çökmez; ekrandaki **ANDROID AYARLARI** düğmesiyle *Ayarlar > Uygulamalar > FK JARVIS > İzinler > Mikrofon > "Uygulamayı kullanırken izin ver"* yolundan açılabilir. Bu sırada yazıyla komut verilebilir. |
| `INTERNET` | Gemini API; bazı cihazlarda konuşma tanıma | — |
| `SET_ALARM` | Saat uygulamasında alarm/zamanlayıcı hazırlama | Normal izin, sorulmaz |
| Erişilebilirlik hizmeti | Ekran etkileşimi (Seviye B) | Yalnızca kullanıcı *Ayarlar > Erişilebilirlik > FK JARVIS ekran kontrolü*'nü kendisi açarsa |

`CALL_PHONE`, `SYSTEM_ALERT_WINDOW`, rehber/SMS izinleri **kullanılmaz**. Arama için yalnızca numaralı arama ekranı açılır; aramayı kullanıcı başlatır.

Sürekli dinleme ve uyandırma sözcüğü bu sürümde **yoktur** (şartnamede opsiyonel). Bu nedenle mikrofon için foreground service gerekmez; mikrofon yalnızca düğmeye basınca ya da asistan bir soru sorduktan sonra kısa süre açılır ve üstteki **● MİKROFON AÇIK** göstergesi yanar.

---

## 4. Gemini API anahtarı

1. https://aistudio.google.com adresinde **Get API key** ile anahtar oluştur.
2. Uygulamada sağ üstteki ⚙ → **GEMINI API ANAHTARI** alanına yapıştır → **KAYDET**.
3. Model adı varsayılan olarak `gemini-2.5-flash`'tır; aynı ekrandan değiştirilebilir (AI Studio'da hesabının erişebildiği bir model adı yaz).

Güvenlik:
- Anahtar APK'ya veya kaynak koda **gömülmez**. Cihazda Android Keystore'daki AES-GCM anahtarıyla şifrelenip saklanır.
- İstekte yalnızca `x-goog-api-key` başlığında gönderilir; URL'ye ve loglara yazılmaz.
- Daha yüksek güvenlik için anahtarı kendi sunucunda tutan bir proxy kullanmak istersen `GeminiProvider(baseUrl = ...)` parametresi buna hazırdır.
- Anahtar yoksa ya da yanlışsa uygulama çökmez; anlaşılır bir mesaj verir ve yerel komutlar çalışmaya devam eder.
- Hata yönetimi: bağlantı 10 sn, okuma 30 sn zaman aşımı; 429 (kota), 5xx, zaman aşımı ve bağlantı hatalarında 2 kez artan beklemeyle yeniden deneme; 401/403 yeniden denenmez.

**Gizlilik:** Gemini'ye yalnızca söylediğin cümlenin metni, son 6 konuşma satırı ve saat/dil bilgisi gönderilir. Ses kaydı, uygulama listesi, ekran içeriği gönderilmez. "YALNIZCA CİHAZ İÇİ" modunda hiçbir şey buluta gitmez.

---

## 5. Desteklenen komutlar (örnekler)

**Cihaz içinde (internet gerekmez):**
- "JARVIS, beni duyuyor musun?"
- "YouTube'u aç", "WhatsApp'ı aç", "Google Haritalar'ı aç", "CapCut'ı aç", "Lightroom'u aç", "Photoshop'u aç"
- "Kamerayı aç", "Ana ekrana dön"
- "Sesini kıs / yükselt" (medya sesi)
- "Saat kaç", "Bugün günlerden ne"
- "5 dakikalık zamanlayıcı kur", "Saat 7 buçukta alarm kur"
- "Son söylediğimi tekrar et", "Tekrarla"
- "Wi-Fi ayarlarını aç", "Bluetooth ayarlarını aç"
- "Kotlin diye ara", "Taksim için yol tarifi", "0555 123 45 67 numarasını ara" (arama ekranını açar)
- Erişilebilirlik açıkken: "Geri git", "Aşağı kaydır", "Bildirimleri aç", "Ayarlar'a dokun", "Şunu yaz merhaba" (onaylı)

**Gemini ile:** "Bugün ne yapmam gerekiyor?", "Bana kısa bir özet ver", serbest sohbet ve sorular, "Ahmet'e WhatsApp'tan geliyorum yaz" (metin hazırlanır, **onay** sorulur, göndermeyi kullanıcı yapar).

> Not: Asistanın takvim, mesaj veya dosyalarına erişimi yoktur; "Bugün ne yapmam gerekiyor?" sorusuna bunu dürüstçe söyler.

### Uygulama açma kuralları
- Paket adı **tahmin edilmez**. Cihazdaki başlatıcı uygulamaları listelenir, söylenen adla eşleştirilir (Türkçe ekler ve "haritalar → Maps" gibi takma adlar dahil).
- Uygulama yoksa: *"…adlı bir uygulamayı bu cihazda bulamadım"* denir.
- Birden fazla eşleşme varsa liste gösterilir ve hangisinin açılacağı sorulur ("ikinci" diyerek ya da dokunarak seçilir).
- Model bir paket adı önerse bile yalnızca cihazda gerçekten kuruluysa kullanılır.

---

## 6. Güvenlik kuralları (özet)

- İzin listesi dışındaki her eylem reddedilir (`unknown`).
- Mesaj hazırlama, paylaşım, ekrana yazı yazma ve "gönder/öde/sil/satın al/paylaş/onayla/şifre…" içeren dokunuşlar **her zaman** onay ister; onay eylem + hedef + içerik ile birlikte sorulur.
- Yalnızca `http/https` bağlantılar açılır (`javascript:`, `intent:`, `file:` reddedilir).
- Parola alanlarına yazılmaz, parola alanına dokunulmaz. CAPTCHA/biyometri aşılmaya çalışılmaz.
- Model metnindeki talimatlar bu kuralları değiştiremez; model yalnızca onay gereksinimini artırabilir.
- Bir eylemin başarılı olduğu yalnızca Android eylemi kabul ettiğinde söylenir.
- Bankacılık gibi bazı uygulamalar erişilebilirlik kontrolünü engelleyebilir; her ekranın kontrol edilebileceği varsayılmaz.
- Google Play'de yayınlamak istersen erişilebilirlik API'si kullanımı için Play politikası beyanı gerekir.

---

## 7. Avatar entegrasyonu

`AvatarBridge`, WebView'daki avatarın gerçek API'sini çağırır:

```js
avatar.setState('idle'|'listening'|'thinking'|'talking'|'executing'|'confirming'|'error'|'micoff')
avatar.setLevel(0..1)   avatar.replay()   avatar.setSpeed(x)   avatar.setSound(bool)
```

Eşleme: IDLE→idle, LISTENING→listening, PROCESSING→thinking, SPEAKING→talking, EXECUTING→executing, CONFIRMING→confirming, ERROR→error (mikrofon izni yoksa bekleme durumunda micoff).

**Dudak senkronizasyonu yoktur.** Dinlerken seviye mikrofonun gerçek RMS değerinden gelir. Konuşurken Android TTS ses genliği vermediği için seviye, TTS'in kelime zamanlamasından (`onRangeStart`) türetilir; avatar ayrıca kendi konuşma ritmini canlandırır.

---

## 8. Testler ve doğrulama durumu

| Kapsam | Durum |
|---|---|
| Birim testleri: Türkçe sınıflandırıcı, güvenlik politikası, uygulama eşleştirici | JVM'de çalıştırıldı |
| Gemini sağlayıcı (MockWebServer): şema ayrıştırma, 429 yeniden deneme, 403, bozuk JSON, güvenlik engeli, anahtarsız çalışma, ağ hatası | JVM'de çalıştırıldı |
| Orkestratör entegrasyon testleri (sahte giriş/çıkış ile): sesli komut → eylem, onaysız hassas eylem yapılmaması, LLM önerisinin doğrulanması, LLM hatasında çökmeme, sağlayıcı yokken yerel yanıt, mikrofon izni reddi, belirsiz uygulama seçimi | JVM'de çalıştırıldı |
| Avatar animasyonları (her iki arayüz, tüm durumlar) | Masaüstü Chromium'da ekran görüntüsüyle kontrol edildi |
| APK derlemesi | GitHub Actions'ta (bu geliştirme ortamında Android SDK yoktu) |

**Gerçek cihazda TEST EDİLMEDİ** (geliştirme ortamında Android cihaz/emülatör yoktu). Şartname §12'deki şu maddeler cihazda doğrulanmalıdır:

1. Türkçe sesli komut algılanıyor mu? — *test edilmedi*
2. Türkçe sesli yanıt (TTS Türkçe ses verisi yüklü mü?) — *test edilmedi*
3. YouTube / WhatsApp açılıyor mu? — *test edilmedi* (eşleştirme mantığı birim testli)
4. Yüklü olmayan uygulamada doğru hata — *test edilmedi* (mantık birim testli)
5. İnternet yokken yerel komutlar — *test edilmedi* (not: bazı cihazlarda Google konuşma tanıma da internet ister; o durumda yazıyla komut çalışır)
6. Gemini hatasında çökmeme — *test edilmedi* (mantık testli)
7. Mikrofon izni reddi — *test edilmedi* (mantık testli)
8. Onaysız hassas eylem olmaması — *test edilmedi* (mantık testli)
9. Avatar senkronu — *test edilmedi*
10. Android 15/16 izin ve servis davranışı — *test edilmedi*

Özellikle cihazda denenmesi gereken riskli noktalar:
- **Erişilebilirlik üzerindeki mikrofon düğmesi:** başka bir uygulama öndeyken Android'in mikrofon erişimine izin verip vermediği cihaza/sürüme göre değişebilir. İzin vermezse ekranda hata görünür; o durumda komutu uygulama içinden verip hedef uygulamaya geçmek gerekir.
- **TTS Türkçe ses verisi:** yüklü değilse yanıtlar ekranda yazılı görünür ama seslendirilmez. *Ayarlar > Sistem > Diller > Metin okuma çıkışı*'ndan Türkçe ses verisi indirilebilir.
- WebView'ın Canvas performansı düşük donanımlı cihazlarda (özellikle NEON arayüzünde) düşük kare hızı verebilir; HIZ ve ARAYÜZ düğmeleriyle hafifletilebilir.

---

## 9. Bilinen sınırlar / sonraki adımlar

- Uyandırma sözcüğü (ör. "Hey JARVIS") yok — eklenirse foreground service (`microphone` tipi) ve bildirim gerekir.
- Takvim/görev entegrasyonu yok.
- LLM sağlayıcısı olarak şu an yalnızca Gemini var; `LLMProvider` arayüzüne yeni sağlayıcı eklenebilir.
- Release imzalama yapılandırması yok (yalnızca debug APK).
