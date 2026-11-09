# SRİUS JARVIS — Android 16 Kişisel Yapay Zekâ Asistanı

Telefonunu sesle yönetebildiğin, seninle Türkçe konuşan, yapay zekâ destekli kişisel asistan.
Kotlin + Jetpack Compose; avatar WebView içinde Canvas ile çizilir. Hedef: **Android 16 (targetSdk 36)**, en düşük Android 8 (minSdk 26).

> **Durum özeti (dürüst):** Kod derleniyor ve 71 birim/entegrasyon testi geçiyor; APK GitHub Actions'ta üretiliyor.
> **Hiçbir özellik gerçek bir Android 16 cihazda test edilmedi** (geliştirme ortamında cihaz/emülatör yoktu). Ayrıntı: [§9](#9-test-ve-doğrulama-durumu).

---

## 1. Arayüz ve avatar

| Arayüz | Görünüm |
|---|---|
| **ART** (varsayılan) | Siyah zemin, neon mavi holografik büst: alttaki ışık noktasından akan parçacıklarla "ASSEMBLING… %" montajı, kontur çizgili baş, turuncu parlayan yüz, parçacıklı dağlar. |
| **NEON** | Parçacık küresinden galaksiye, oradan mor/altın neon figüre dönüşen açılış; ışık saçları, yıldırımlar. |

Alt çubuktaki **ARAYÜZ** düğmesi veya Ayarlar > Arayüz ile değişir. Arayüz renkleri seçili avatara uyar (ART'ta neon mavi). Avatar çizim kodu (`app/src/main/assets/avatar/`) bu sürümde değiştirilmedi; yalnızca ses sistemine bağlandı.

Avatar durumları: **IDLE** (bekleme) · **LISTENING** (dinleme) · **THINKING** (düşünme) · **SPEAKING** (konuşma) · **EXECUTING** (işlem) · **CONFIRMING** (onay bekleme) · **ERROR** (hata).

**Dudak senkronu:** Yanıt önce ses dosyasına sentezlenir, dosyanın gerçek ses genliği (20 ms'lik zarf) çıkarılır ve çalma konumuyla eş zamanlı olarak avatarın yüzüne/ağzına verilir. Bu, genliğe dayalı bir senkrondur; fonem (harf sesi) tabanlı dudak şekli değildir. Sentez başarısız olursa kelime zamanlamasına düşülür.

---

## 2. Mimari (şartnamedeki modüller)

| Şartname modülü | Dosya |
|---|---|
| VoiceEngine | `voice/VoiceEngine.kt` (mikrofon oturumu), `voice/BargeInAndWake.kt` (araya girme + uyandırma döngüsü), `voice/VoiceSignal.kt` |
| SpeechRecognizer | `voice/JarvisSpeechRecognizer.kt` (Android SpeechRecognizer, tr-TR, varsa cihaz içi) |
| TextToSpeechEngine | `voice/TextToSpeechEngine.kt` (Türkçe TTS + dudak senkronu) |
| GeminiAIService | `llm/GeminiAIService.kt` |
| AIProviderManager | `llm/AIProviderManager.kt` — Gemini, OpenAI (`OpenAICompatibleService`), Claude (`ClaudeAIService`, resmi Anthropic Java SDK), yerel model |
| AssistantCore | `core/AssistantCore.kt` (durum makinesi, sesli oturum, onaylar) |
| CommandRouter | `core/CommandRouter.kt` (+ `IntentClassifier.kt`, `SafetyPolicy.kt`) |
| AndroidAppController | `apps/AndroidAppController.kt` (+ `AppMatcher.kt`, `CalendarReader.kt`) |
| AccessibilityController | `accessibility/AccessibilityController.kt` + `JarvisAccessibilityService.kt` |
| PermissionManager | `permissions/PermissionManager.kt` |
| AvatarBridge | `avatar/AvatarBridge.kt` |
| ConversationManager | `data/ConversationManager.kt` |
| Ön plan hizmeti | `service/AssistantSessionService.kt` |

**Akış:** KONUŞ / "JARVIS" → konuşma tanıma (tr-TR, konuşma bitince otomatik işler) → **cihaz içi sınıflandırıcı** (internetsiz) → anlaşılamazsa seçili **yapay zekâ sağlayıcısı** (yalnızca JSON komut önerisi döndürür) → **güvenlik politikası** (izin listesi, argüman doğrulama, onay) → gerekirse **onay** (ekranda ya da sesle "evet/hayır") → **Android eylemi** → sonucun gerçek durumu **sesli** okunur. Kesintisiz sohbet açıksa ardından yeniden dinlenir.

---

## 3. Kurulum

### APK'yı indirme
Depodaki **Releases** sayfasında "apk-latest" sürümünde iki dosya var (her derlemede güncellenir):

| Dosya | İçerik |
|---|---|
| `SRIUS_JARVIS_tam.apk` | Tüm özellikler, erişilebilirlik ile ekran kontrolü dahil |
| `SRIUS_JARVIS_basit.apk` | Erişilebilirlik hizmeti yok (geri git/kaydır/dokun/yaz/ekran görüntüsü komutları çalışmaz); diğer her şey aynı |

İkisi de release (hata ayıklanamaz) derlemedir, v1+v2+v3 imzalıdır ve aynı paket adını kullanır; yalnızca birini kur.

### Google Play'e yükleme
Play için hazırlık, kendi imza anahtarın, gizlilik politikası ve Play Console beyanları: **[docs/GOOGLE_PLAY_TR.md](docs/GOOGLE_PLAY_TR.md)**. Play'e `.aab` dosyası yüklenir (CI'da `SRIUS_JARVIS_tam.aab` / `SRIUS_JARVIS_basit.aab`).

### Telefona kurulamıyorsa
1. **"Play Protect tarafından engellendi" / "Güvenli olmayan uygulama"**: *Daha fazla ayrıntı → Yine de yükle*. Bu seçenek yoksa Play Store → profil → Play Protect → ⚙ → "Uygulamaları Play Protect ile tara" seçeneğini kurulum süresince kapat, kurduktan sonra tekrar aç. Hâlâ engelleniyorsa **basit** sürümü kur: Google'ın dolandırıcılık koruması, dışarıdan yüklenen ve erişilebilirlik hizmeti isteyen uygulamaları bazı ülkelerde doğrudan engeller.
2. **"Bilinmeyen uygulamalar yüklenemez"**: Ayarlar → Uygulamalar → (dosyayı açtığın uygulama: Dosyalar, Chrome, Claude…) → *Bilinmeyen uygulamaları yükle* → izin ver.
3. **"Uygulama yüklenmedi — paket mevcut bir paketle çakışıyor"**: telefonda daha önce farklı imzalı bir SRİUS/FK JARVIS sürümü var. Onu kaldırıp yeniden kur.
4. **"Ayrıştırma hatası" / "Paket geçersiz"**: dosya eksik inmiş; yeniden indir (tam sürüm yaklaşık 20–25 MB olmalı). Zip içinden değil, `.apk` dosyasının kendisinden kur.
5. **Gelişmiş Koruma (Advanced Protection) açık**: Android 16'da bu mod dışarıdan uygulama yüklemeyi tamamen engeller; kurulum için geçici olarak kapatman gerekir.
6. **Erişilebilirlik hizmeti açılamıyor ("Kısıtlanmış ayar")**: Ayarlar → Uygulamalar → SRİUS JARVIS → ⋮ (sağ üst) → *Kısıtlanmış ayarlara izin ver*, sonra Erişilebilirlik'ten hizmeti aç.

### Kendin derleme
Android Studio (Meerkat+) ya da JDK 17 + Android SDK 36:
```bash
./gradlew testTamReleaseUnitTest                  # testler
./gradlew assembleTamRelease assembleBasitRelease # app/build/outputs/apk/{tam,basit}/release/*.apk
```

### İlk açılış
1. Ayarlar (⚙) → **Yapay zekâ sağlayıcısı** seç → API anahtarını gir (§4).
2. **KONUŞ** düğmesine bas → mikrofon iznini ver.
3. İsteğe bağlı: **Uyandırma sözcüğü**, **Ekran kontrolü (erişilebilirlik)**.

---

## 4. Yapay zekâ sağlayıcıları ve API anahtarları

| Sağlayıcı | Anahtar | Varsayılan model | İnternet araştırması |
|---|---|---|---|
| Gemini | aistudio.google.com → Get API key | `gemini-2.5-flash` | Google Search grounding |
| OpenAI | platform.openai.com → API keys | `gpt-5-mini` | Responses API `web_search` |
| Claude | console.anthropic.com → API Keys | `claude-opus-5-5` (efor: düşük; sunucu tarafı yedek model açık) | `web_search` aracı |
| Yerel model | gerekmez | (sen yazarsın, ör. `llama3.1`) | yok |
| Yalnızca cihaz içi | — | — | — |

- Model adları Ayarlar'dan değiştirilebilir (hesabının erişebildiği bir ad yaz).
- **Anahtarlar APK'ya/kaynak koda gömülmez.** Cihazda Android Keystore (AES-GCM) ile şifreli saklanır, loglanmaz, URL'ye yazılmaz.
- **Sunucu proxy'si (önerilir):** "Özel sunucu / proxy" alanına kendi https proxy adresini yaz, anahtarı boş bırak; anahtarı proxy ekler. (Gemini: `/v1beta/models/...`, OpenAI: `/chat/completions` ve `/responses`, Claude: `/v1/messages` yollarını hedef API'ye iletmelidir.)
- **Yerel model:** Ollama / LM Studio gibi OpenAI uyumlu sunucu adresi, ör. `http://192.168.1.20:11434/v1`. `http` yalnızca yerel ağ/loopback adreslerinde kabul edilir; diğer her adres `https` olmalı.
- Hata yönetimi: zaman aşımı, bağlantı hatası, kota (429) ve sunucu hatalarında sınırlı yeniden deneme; anahtar yoksa/yanlışsa uygulama çökmez, anlaşılır mesaj verir; internet yoksa yerel komutlar çalışmaya devam eder.
- **Gönderilen veri:** yalnızca söylediğin cümlenin metni, son 6 konuşma satırı ve saat. Ses kaydı, takvim, ekran içeriği, uygulama listesi gönderilmez.
- Yeni sağlayıcı eklemek: `LLMProvider` arayüzünü uygula, `AIProviderManager` listesine ekle.

---

## 5. Sesli etkileşim

- **Türkçe tanıma ve Türkçe sesli yanıt** (Android TextToSpeech; hız ve ton ayarlanabilir).
- **Konuşma bitince otomatik işleme;** sessizlik süresi ayarlanabilir (0,5–5 sn).
- **Kesintisiz sohbet:** yanıttan sonra otomatik yeniden dinler. "Görüşürüz", "dur", **BİTİR** düğmesi ya da iki kez üst üste sessizlik oturumu bitirir. Yazılı komutlar oturumu başlatmaz.
- **Araya girme (barge-in, varsayılan kapalı):** asistan konuşurken konuşmaya başlarsan yanıt kesilir ve seni dinler. Mikrofon yankı giderici ile açılır; hoparlör sesi yüksekse yanlış tetiklenebilir, kulaklıkla en iyisi.
- **Yanıtı durdurma:** DUR düğmesi ya da "dur / sus".
- **"JARVIS" uyandırma sözcüğü (varsayılan kapalı):** açınca bildirimli ön plan hizmeti başlar. Sistem konuşma tanıyıcısını döngüyle kullanır (ayrı bir anahtar sözcük modeli değildir) → **pil tüketir**, bazı cihazlarda her turda kısa bir ses çıkabilir. Android 13+ cihaz içi tanıma varsa internetsiz çalışır. Ekran kapalıyken varsayılan olarak durur. "JARVIS, YouTube'u aç" gibi tek cümlede komut verebilirsin.
- **Yazılı komut** her zaman alttaki metin kutusundan verilebilir.

### Ön plan hizmeti ve Android 16 kuralları
- Tür: `microphone` (`FOREGROUND_SERVICE_MICROPHONE`). Yalnızca uygulama öndeyken başlatılır (Android 14+ kuralı); arka planda gizlice başlatılmaz.
- Çalıştığı durumlar: uyandırma sözcüğü açıkken, ya da sesli sohbet sırasında (ör. "YouTube'u aç" dedikten sonra uygulama arkaya geçse de sohbet sürsün diye). Oturum bitince kendini kapatır.
- Bildirimde **Dinle** ve **Durdur** düğmeleri vardır. Telefon yeniden başlatılınca hizmet kendiliğinden açılmaz (mikrofon kuralı); uygulamayı bir kez açman yeterli.
- Uygulama yeniden açıldığında konuşma geçmişi ve oturum durumu korunur.

---

## 6. Telefon kontrolü ve komutlar

**Cihaz içinde anlaşılan örnekler (internet gerekmez):**

| Söyle | Ne olur |
|---|---|
| "JARVIS, YouTube'u aç" / "WhatsApp'ı aç" / "Chrome'u aç" / "CapCut'ı başlat" / "Lightroom'u aç" / "Photoshop'u aç" / "Google Haritalar'ı aç" | Cihazdaki uygulama listesinde bulunur ve açılır. Yoksa "bulamadım" der; birden fazla eşleşmede sorar. |
| "YouTube'da lofi müzik ara" | YouTube uygulamasında arama (yoksa tarayıcıda) |
| "Kamerayı aç", "Galeriyi aç" | Kamera / galeri |
| "YouTube'u kapat" | Android başka uygulamaları kapatmaya izin vermez: uygulama öndeyse (erişilebilirlikle doğrulanır) ana ekrana dönerek arka plana alır ve bunu açıkça söyler. |
| "Sesini yükselt / kıs" | Medya sesi |
| "Wi-Fi / Bluetooth / bildirim ayarlarını aç" | İlgili ayar ekranı |
| "Saat kaç", "Bugün günlerden ne" | Yerel yanıt |
| "Bugün neler yapmam gerekiyor?" | Takvimden bugünün etkinliklerini **okur** (ilk seferde takvim izni sorulur; veri cihazdan çıkmaz) |
| "5 dakikalık zamanlayıcı kur", "Saat 7 buçukta alarm kur" | Saat uygulamasında hazırlar |
| "Bu metni kopyala" | Son yanıtı/taslağı panoya kopyalar |
| "Paylaşım ekranını aç" / "Bunu paylaş" | Son metni paylaşım ekranında açar — **onay ister**, göndermeyi sen yaparsın |
| "Ekran görüntüsü al" | Erişilebilirlik açıksa alır; değilse tuş kombinasyonunu söyler |
| "Geri git", "Aşağı kaydır", "Bildirimleri aç", "Ayarlar'a dokun", "Şunu yaz merhaba", "Seçili metni kopyala" | Erişilebilirlik hizmetiyle (yazma her zaman onaylı) |
| "Kotlin diye ara", "Taksim için yol tarifi", "0555… numarasını ara" | Web araması / harita / numaralı arama ekranı |
| "Görüşürüz", "Dur" | Sohbeti bitirir / susar |

**Yapay zekâ ile:** "Bana günlük haberleri oku" ve "internette ... araştır" (web araması yapan sağlayıcı gerekir), "Bana bir Instagram paylaşım metni hazırla" (metin ekranda gösterilir, panoya kopyalanır; paylaşma ayrıca onaylı), serbest sohbet ve sorular.

### Erişilebilirlik (Seviye B)
Yalnızca sen *Ayarlar > Erişilebilirlik > SRİUS JARVIS ekran kontrolü*'nü açarsan çalışır; uygulama bunu gizlice açamaz. Ekran içeriği yalnızca istenen tek işlem anında okunur, saklanmaz, gönderilmez. Açıkken diğer uygulamaların üzerinde küçük bir mikrofon düğmesi görünür. Bazı uygulamalar (bankacılık vb.) bu kontrolü engelleyebilir. Google Play'de yayınlamak için erişilebilirlik API'si kullanım beyanı gerekir.

---

## 7. Güvenlik

- Mesaj gönderme/hazırlama, sosyal medya paylaşımı, ekrana yazı yazma ve "gönder / öde / satın al / sil / paylaş / onayla / şifre…" içeren dokunuşlar **her zaman onay** ister; onay eylem + hedef + içerikle birlikte sorulur.
- Yapay zekâ yalnızca izin listesindeki komutları önerebilir; listede olmayan reddedilir. Model onay gerekliliğini kaldıramaz, yalnızca ekleyebilir.
- Yalnızca `http/https` bağlantılar açılır (`javascript:`, `intent:`, `file:` reddedilir).
- Parolalar kaydedilmez/gönderilmez; parola alanlarına yazılmaz, kopyalanmaz. CAPTCHA/biyometri aşılmaya çalışılmaz.
- Gerçekleşmeyen işlem yapılmış gibi söylenmez: yanıt, Android eyleminin gerçek sonucundan üretilir.
- Kullanıcıdan habersiz işlem yapılmaz; mikrofon açıkken üstte kırmızı gösterge, arka planda bildirim görünür.

### İmzalama
- **Varsayılan:** depodaki `signing/sirius-test.jks` test anahtarı (gizli değildir). Yayınlanan APK'lar bununla imzalanır; böylece yeni sürümler eskisinin üzerine kurulur.
- **Üretim / Play Store:** `scripts/create_release_keystore.sh` ile anahtar oluştur, `keystore.properties.example`'ı `keystore.properties` olarak kopyalayıp doldur (git'e eklenmez), `./gradlew assembleRelease`. CI'da `SRIUS_KEYSTORE_BASE64`, `SRIUS_STORE_PASSWORD`, `SRIUS_KEY_ALIAS`, `SRIUS_KEY_PASSWORD` sırları tanımlıysa imzalı release APK üretilir; değilse imzasız release APK.

---

## 8. İzinler

| İzin | Neden |
|---|---|
| `RECORD_AUDIO` | Sesli komut (ilk KONUŞ'ta sorulur). Reddedilirse ekrandaki **ANDROID AYARLARI** ile *Ayarlar > Uygulamalar > SRİUS JARVIS > İzinler > Mikrofon* yolundan açılır. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Sesli oturum / uyandırma sözcüğü hizmeti |
| `POST_NOTIFICATIONS` | Hizmet bildirimi (Android 13+) |
| `READ_CALENDAR` | Yalnızca "bugün ne yapmam gerekiyor" sorulduğunda; salt okuma |
| `INTERNET` | Yapay zekâ sağlayıcıları; bazı cihazlarda konuşma tanıma |
| `SET_ALARM` | Saat uygulamasında alarm/zamanlayıcı hazırlama |

`CALL_PHONE`, SMS, rehber, depolama, "diğer uygulamaların üzerinde göster" izinleri kullanılmaz.

---

## 9. Test ve doğrulama durumu

| Kapsam | Durum |
|---|---|
| 71 birim + entegrasyon testi (Türkçe komut sınıflandırma, güvenlik politikası, uygulama eşleştirme, Gemini/OpenAI/yerel/Claude sağlayıcıları sahte sunucuyla, ses zarfı, ses etkinliği algılama, uyandırma sözcüğü, ajanda metni, çekirdek akış: sesli oturum, araya girme, onay, izin, araştırma, taslak/kopyalama/paylaşım) | Geçti (JVM) — CI'da da çalışır |
| Android derlemesi, debug APK, `targetSdkVersion 36` ve imza doğrulaması | GitHub Actions'ta |
| Avatar animasyonları (iki arayüz, tüm durumlar) | Masaüstü Chromium'da ekran görüntüsüyle kontrol edildi |

**Gerçek cihazda TEST EDİLMEDİ** — aşağıdakilerin hepsi Android 16 telefonda doğrulanmalı:
Türkçe tanıma/TTS kalitesi · dudak senkronunun avatarla uyumu · kesintisiz sohbet ve araya girme (yankı ile yanlış tetikleme) · uyandırma sözcüğü (pil, tanıyıcı sesleri, cihaz içi Türkçe model) · ön plan hizmeti ve bildirim · uygulama açma/YouTube araması/galeri · erişilebilirlik işlemleri ve ekran görüntüsü · takvim okuma · üç bulut sağlayıcısının gerçek API'lerle çalışması (OpenAI Responses API web araması özellikle) · erişilebilirlik üstündeki mikrofon düğmesinin arka planda mikrofon alabilmesi.

---

## 10. Bilinen sınırlar

- Android, bir uygulamanın başka uygulamaları **kapatmasına** izin vermez; yalnızca arka plana alınabilir.
- Uyandırma sözcüğü özel bir anahtar sözcük motoru değildir (pil ve gizlilik açısından bunu bil; varsayılan kapalı).
- Dudak senkronu genliğe dayalıdır, fonem tabanlı değildir.
- Konuşma tanıma cihazın tanıma hizmetine bağlıdır (çoğunlukla Google); bazı cihazlarda internet ister.
- Takvime yazma, mesajı otomatik gönderme, sosyal medyada otomatik yayın bilinçli olarak yoktur (onaylı paylaşım ekranı var).
