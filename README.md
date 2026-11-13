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

**Dört rol, tek asistan:** kişisel asistan (ses, hafıza, telefon kontrolü) · iş asistanı (taslaklar, toplantı hazırlığı, notlar, araştırma) · planlayıcı (görevler, takvim, rutinler) · iş yapan ajan (plan çıkarır, araçlarla uygular, rapor verir). Hepsi sesle de kullanılır.

**Holografik arayüz:** alt çubukta 7 bölme (ASİSTAN, MERKEZ, PLAN, AJAN, TAKVİM, WEB, MENÜ); diğer bölümler MENÜ'den açılır. Bölmeler 3B dönerek geçer, paneller kademeli açılır.

| Bölme | İçerik |
|---|---|
| **ASİSTAN** | Avatar, HUD başlığı (durum, mikrofon, yapay zekâ, oturum ışıkları), canlı metin dökümü, onay/seçim/taslak panelleri, ses seviyesi göstergesi, animasyonlu mikrofon küresi, yazılı komut |
| **MERKEZ** | Komuta merkezi: asistanın durumuna ve sesine tepki veren **3B reaktör çekirdeği**, canlı halka göstergeler (pil, bellek, depolama, sıcaklık), ağ hızı grafiği, sıradaki görevler ve rutin, **pano zekâsı** (kopyalanan metni çevir / özetle / açıkla / düzelt — pano yalnızca düğmeye basınca okunur), döküm tarzı kayıt |
| **PLAN** | Bugünün özeti (etkinlik, bugünkü/açık görev sayıları), **görevler** (tarih, öncelik, tamamla), **rutinler ve hatırlatıcılar** (bir kez / her gün / hafta içi / hafta sonu; hatırlat, sesli söyle, ajan görevi), sessiz saatler, **notlar**; GÜNÜMÜ / HAFTAMI PLANLA |
| **AJAN** | Görev yaz ya da kategorili hazır görev seç (KİŞİSEL, PLANLAMA, İŞ, ARAŞTIRMA). **Profil**: otomatik, GENEL, ARAŞTIRMACI, PLANLAYICI, İŞ ASİSTANI. Önce plan gösterilir, adımlar canlı listelenir, onay gereken adımda ONAYLA/REDDET, DURDUR. Biten görevler **RAPORLAR**'da saklanır (sesli oku, notlara kaydet) |
| **TAKVİM** | Haftalık şerit, seçili günün etkinlikleri (dokununca takvim uygulamasında açılır), yeni etkinlik formu (başlık, saat, süre, yer) |
| **WEB** | Uygulama içi tarayıcı: adres/arama çubuğu, geri/ileri/yenile, güvenli bağlantı göstergesi, yapay zekâyla **sayfa özeti**, dış tarayıcıda açma. Asistan ve ajan da bu tarayıcıyı kullanır |
| **MENÜ → UYGULAMA** | Cihazdaki uygulamalar: arama, hızlı erişim, tek dokunuşla açma |
| **MENÜ → SOHBET** | Konuşma geçmişi (yalnızca metin, cihazda), silme |
| **MENÜ → HAFIZA** | Kalıcı hafıza (kategorilere göre, tek tek/topluca silme, yapay zekâyla paylaşım anahtarı), takip edilen konular, geri alınabilir işlemler |
| **MENÜ → ARAYÜZ** | Avatar (ART/NEON), animasyon hızı, vurgu rengi (otomatik/mavi/mor/altın/yeşil), geçiş efektleri ve ızgara aç/kapat |
| **MENÜ → SUNUCU** | Sağlayıcı kartları (Gemini, OpenAI, Claude, yerel, cihaz içi), API anahtarı, model, proxy/yerel sunucu adresi, gerçek **bağlantı testi** (yanıt süresiyle), **Gemini Live** (gerçek zamanlı konuşma) ayarları |
| **MENÜ → SİSTEM** | Mikrofon, bildirim, takvim, ekran kontrolü izinleri; konuşma tanıma ve Türkçe seslendirme durumu; arka plan hizmeti; sürüm ve **imza SHA-256** (test/üretim anahtarı) |
| **MENÜ → AYARLAR** | Asistan adı, kesintisiz sohbet, araya girme, sessizlik süresi, konuşma hızı/tonu, uyandırma sözcüğü, gizlilik |

### Gemini Live — gerçek zamanlı konuşma
SUNUCU → Gemini → **Gemini Live** → "Gerçek zamanlı konuşma"yı aç. Artık **KONUŞ** (CANLI) düğmesi bir canlı oturum açar:
- Mikrofon sesi WebSocket ile doğrudan Gemini Live'a akar (16 kHz PCM); Gemini kendi sesiyle anında yanıt verir (24 kHz PCM).
- Avatarın ağzı/ışığı modelin **gerçek ses genliğiyle** hareket eder; konuşma metni ekranda canlı yazılır.
- Söz kesme: Live API konuşmanı algılayınca yanıtı keser. "Araya girme" kapalıysa model konuşurken mikrofon gönderilmez (hoparlör yankısı kendini kesmesin diye).
- Telefon işlemleri modelin `phone_action` aracıyla gelir ve **aynı güvenlik politikasından** geçer; onay gerekenler yalnızca ekrandaki ONAYLA ile yapılır. Güncel bilgi için Google Search aracı açıktır.
- Varsayılan Live modeli: `gemini-3.1-flash-live-preview` (önizleme). Sesler: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr. Model adı değişirse SUNUCU'dan güncellenebilir.
- Normal (Live olmayan) modda Gemini metin modeli varsayılanı: `gemini-3.8-flash` (düşünme düzeyi: düşük).
- Gizlilik: canlı modda ses, oturum açıkken Google'a gider; oturum BİTİR ile kapanır.

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
| "Yarın neler var?", "Cuma programım ne?" | O günün etkinliklerini okur |
| "Bunu hatırla: annemin doğum günü 3 mayıs" | Kalıcı hafızaya kaydeder ve kaydettiğini söyler (parola/kart/kod kaydedilmez) |
| "Benim hakkımda ne biliyorsun?", "Hafızanı sil" (onaylı) | Hafızadakileri okur / siler |
| "Geri al" | Asistanın yaptığı son değişikliği geri alır (takvime eklenen etkinliği siler, hafıza kaydını geri alır) |
| "Pil durumu ne?", "Telefonun durumu" | Pil, sıcaklık, bellek, depolama, ağ |
| "Uzay araştırmaları haberlerini takip et", "Takip listem", "… takibini bırak" | Günde bir kez yeni gelişme aranır, varsa bildirim gelir (kripto/borsa kabul edilmez) |
| "Kameraya bak ve bu bitki ne", "Ne görüyorsun?" | Kamera uygulaması açılır; çektiğin fotoğraf yapay zekâya sorulur (Gemini veya OpenAI) |
| "Görev ekle: yarın raporu gönder", "Görevlerim neler?", "Rapor görevini tamamla" | Yapılacaklar listesi (PLAN bölmesiyle aynı liste) |
| "Not al: müşteri cuma günü dönecek", "Notlarımda teklif ara" | Notlar |
| "Yarın saat 9'da toplantıyı hatırlat", "Her gün saat 8'de ilacımı hatırlat", "Hatırlatıcılarım" | Hatırlatıcı / rutin kurar; zamanı gelince bildirim gelir, uygulama açıksa sesli söyler |
| "Neler yapabilirsin?", "Sınırların neler?" | Yapabildiklerini ve yapamadıklarını telefonun **o anki** durumundan (izinler, sağlayıcı, erişilebilirlik) üreterek söyler |
| "Günaydın", "Brifing ver" | Ajan: saat, bugünkü takvim, hafızadaki hedefler ve günün haberleri ile kısa sabah brifingi |
| "Ajan, cumartesi için sinema seanslarını araştır ve uygunsa takvimime ekle" | Görevi AJAN'a verir; adımlar AJAN bölmesinde izlenir, takvime yazma onaylı |

**Yapay zekâ ile:** "Yarın saat 3'te diş hekimi randevumu takvime ekle" (her zaman onay sorar), "sahibinden.com'u aç" (uygulama içi tarayıcıda), "Bana günlük haberleri oku" ve "internette ... araştır" (web araması yapan sağlayıcı gerekir), "Bana bir Instagram paylaşım metni hazırla" (metin ekranda gösterilir, panoya kopyalanır; paylaşma ayrıca onaylı), serbest sohbet ve sorular.

### Erişilebilirlik (Seviye B)
Yalnızca sen *Ayarlar > Erişilebilirlik > SRİUS JARVIS ekran kontrolü*'nü açarsan çalışır; uygulama bunu gizlice açamaz. Ekran içeriği yalnızca istenen tek işlem anında okunur, saklanmaz, gönderilmez. Açıkken diğer uygulamaların üzerinde küçük bir mikrofon düğmesi görünür. Bazı uygulamalar (bankacılık vb.) bu kontrolü engelleyebilir. Google Play'de yayınlamak için erişilebilirlik API'si kullanım beyanı gerekir.

### Ajan nasıl çalışır
Her adımda yapay zekâ izinli araçlardan birini seçer (internette araştır, tarayıcıda ara/aç/oku, takvimi oku/ekle, hafızaya bak/kaydet, telefon durumu, takip edilen konular, izinli telefon eylemleri), araç **telefonda gerçekten** çalışır ve sonucu yapay zekâya geri verilir; en fazla 8 adım. Web sayfası ve arama metinleri yapay zekâya *veri* olarak verilir, içlerindeki talimatlara uyulmaz. Takvime yazma ve onay gerektiren telefon eylemleri yalnızca ONAYLA'ya basınca yapılır; reddedilen işlem tekrar denenmez. Tüm sağlayıcılarla çalışır (Gemini, OpenAI, Claude, yerel); internet araştırması için Gemini, OpenAI ya da Claude gerekir.

### Ajan profilleri, derin araştırma ve rutinler (Mark LVII uyarlaması)
- **Profiller** (`agent/AgentRunner.kt` içindeki `AgentProfiles`; Mark LVII'deki `agents/` dosyalarının karşılığı): her profilin araç listesi, adım bütçesi ve talimatı vardır. Yeni profil eklemek için listeye bir kayıt eklemek yeterlidir.
- **Derin araştırma (ARAŞTIRMACI):** alt soruları planlar, kaynakları uygulama içi tarayıcıda açıp okur; rapora koyacağı alıntıyı `verify_quote` ile **kodda**, gerçekten okunan sayfa metnine karşı denetler. Bulunamayan alıntı "doğrulanamadı" olarak döner.
- **Raporlar:** biten her görev (başarılı ya da başarısız) cihazda saklanır; uygulama arka plandayken biterse bildirim gelir.
- **Rutin motoru:** tek alarm en yakın rutine kurulur; telefon yeniden başlayınca yeniden kurulur. Uygulama ekranda değilken rutin yalnızca bildirim gösterir — seslendirme ve ajan görevi bildirime dokununca başlar. Kendiliğinden ajan görevi başlatan rutin sesle kurulurken onay ister. "Tam zamanlı alarm" özel izni istenmediğinden Android bildirimi birkaç dakika geciktirebilir.
- **Sessiz saatler** (varsayılan 23:00–07:00): tekrarlayan rutinler konuşmaz; belirli bir an için kurulan tek seferlik hatırlatıcı çalışır.
- Mark LVII'den alınmayanlar: masaüstü denetimi (dosya, pencere, PC ayarları, ekran yakalama), Whisper/Piper/ElevenLabs ses motorları, telefon panosu, eklenti yükleyici, kendiliğinden "yoklama" konuşmaları ve görevlerin yeniden başlatmada kaldığı adımdan sürmesi (Android arka plan kısıtları).

### Masaüstü JARVIS'ten (main.py) alınanlar
Kalıcı hafıza (kaydet / hatırla / ara), günlük açılış brifingi, arka planda konu takibi, geri alma, sistem durumu ve görsel analiz Android'e uyarlandı. Alınmayanlar: Windows'a özgü işlemler (alt süreçler, bilgisayar ayarları, ekran yakalama, ses cihazı seçimi) ve "proaktif mod" (kullanıcı sormadan konuşmak; habersiz işlem yapmama kuralı gereği).

---

## 7. Güvenlik

- Mesaj gönderme/hazırlama, sosyal medya paylaşımı, ekrana yazı yazma ve "gönder / öde / satın al / sil / paylaş / onayla / şifre…" içeren dokunuşlar **her zaman onay** ister; onay eylem + hedef + içerikle birlikte sorulur.
- Yapay zekâ yalnızca izin listesindeki komutları önerebilir; listede olmayan reddedilir. Model onay gerekliliğini kaldıramaz, yalnızca ekleyebilir.
- Yalnızca `http/https` bağlantılar açılır (`javascript:`, `intent:`, `file:` reddedilir).
- Parolalar kaydedilmez/gönderilmez; parola alanlarına yazılmaz, kopyalanmaz. CAPTCHA/biyometri aşılmaya çalışılmaz.
- Gerçekleşmeyen işlem yapılmış gibi söylenmez: yanıt, Android eyleminin gerçek sonucundan üretilir.
- Takvime etkinlik ekleme her zaman onaylıdır; yalnızca yeni etkinlik eklenir, mevcutlar değiştirilmez/silinmez (geri al hariç: yalnızca asistanın eklediği etkinlik).
- Uygulama içi tarayıcı: yalnızca http/https, Safe Browsing açık, dosya erişimi kapalı, kamera/mikrofon/konum istekleri reddedilir, `intent:` bağlantıları uygulama başlatmaz.
- Hafıza cihazda saklanır, HAFIZA ekranında görülür ve silinir; parola, kart, IBAN ve kodlar kaydedilmez. "Yapay zekâ hafızayı kullansın" açıksa kısa özet seçili sağlayıcıya bağlam olarak gönderilir.
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
| `READ_CALENDAR` | TAKVİM bölmesi, ajanda soruları ve ajanın takvim aracı; ilk kullanımda sorulur |
| `WRITE_CALENDAR` | Yalnızca onayladığın bir etkinlik eklenirken sorulur. Verilmezse takvim uygulamasının "yeni etkinlik" ekranı doldurularak açılır |
| `INTERNET` | Yapay zekâ sağlayıcıları; bazı cihazlarda konuşma tanıma |
| `SET_ALARM` | Saat uygulamasında alarm/zamanlayıcı hazırlama |
| `RECEIVE_BOOT_COMPLETED` | Kurduğun hatırlatıcı/rutin alarmlarını telefon yeniden başlayınca yeniden kurmak |

"Kameraya bak" için kamera izni istenmez: telefonun kamera uygulaması açılır, fotoğrafı sen çekersin; fotoğraf küçültülüp yalnızca seçili yapay zekâ sağlayıcısına gönderilir ve önbellekten silinir. Konu takibi için arka plan işi (WorkManager) yalnızca en az bir konu eklendiğinde, günde bir kez ve internet varken çalışır.

`CALL_PHONE`, SMS, rehber, depolama, "diğer uygulamaların üzerinde göster" izinleri kullanılmaz.

---

## 9. Test ve doğrulama durumu

| Kapsam | Durum |
|---|---|
| 108 birim + entegrasyon testi (görevler, notlar, rutin zamanlaması ve sessiz saatler, ajan profilleri ve plan ayrıştırma dahil; önceki kapsama ek olarak: Türkçe tarih/saat çözümleme, takvime ekleme onayı, ajan döngüsü — araç çağırma, onay/ret, bilinmeyen araç, bozuk JSON, adım sınırı, sağlayıcı hatası —, hafıza kayıt/arama/gizli bilgi reddi/geri alma, konu takibi, yeni sesli komutlar) (Türkçe komut sınıflandırma, güvenlik politikası, uygulama eşleştirme, Gemini/OpenAI/yerel/Claude sağlayıcıları sahte sunucuyla, ses zarfı, ses etkinliği algılama, uyandırma sözcüğü, ajanda metni, çekirdek akış: sesli oturum, araya girme, onay, izin, araştırma, taslak/kopyalama/paylaşım) | Geçti (JVM) — CI'da da çalışır |
| Android derlemesi, debug APK, `targetSdkVersion 36` ve imza doğrulaması | GitHub Actions'ta |
| Avatar animasyonları (iki arayüz, tüm durumlar) | Masaüstü Chromium'da ekran görüntüsüyle kontrol edildi |

**Gerçek cihazda TEST EDİLMEDİ** — aşağıdakilerin hepsi Android 16 telefonda doğrulanmalı:
Türkçe tanıma/TTS kalitesi · dudak senkronunun avatarla uyumu · kesintisiz sohbet ve araya girme (yankı ile yanlış tetikleme) · uyandırma sözcüğü (pil, tanıyıcı sesleri, cihaz içi Türkçe model) · ön plan hizmeti ve bildirim · uygulama açma/YouTube araması/galeri · erişilebilirlik işlemleri ve ekran görüntüsü · takvim okuma · üç bulut sağlayıcısının gerçek API'lerle çalışması (OpenAI Responses API web araması özellikle) · erişilebilirlik üstündeki mikrofon düğmesinin arka planda mikrofon alabilmesi · ajanın gerçek sağlayıcılarla çok adımlı görevleri · takvime yazma (hesaba göre yazılabilir takvim) · uygulama içi tarayıcıda sayfa okuma/özet · konu takibi bildirimi (WorkManager zamanlaması) · kameraya bak (kamera uygulaması ve görüntü analizi) · rutin/hatırlatıcı alarmlarının zamanında gelmesi (Doze, üretici pil kısıtları, yeniden başlatma) · ajan profillerinin gerçek sağlayıcıyla davranışı ve alıntı doğrulama.

---

## 10. Bilinen sınırlar

- Android, bir uygulamanın başka uygulamaları **kapatmasına** izin vermez; yalnızca arka plana alınabilir.
- Uyandırma sözcüğü özel bir anahtar sözcük motoru değildir (pil ve gizlilik açısından bunu bil; varsayılan kapalı).
- Dudak senkronu genliğe dayalıdır, fonem tabanlı değildir.
- Konuşma tanıma cihazın tanıma hizmetine bağlıdır (çoğunlukla Google); bazı cihazlarda internet ister.
- Takvime yazma, mesajı otomatik gönderme, sosyal medyada otomatik yayın bilinçli olarak yoktur (onaylı paylaşım ekranı var).

---

## Lisans ve teşekkür

Bu proje **MIT lisansı** ile lisanslıdır (`LICENSE`): telif ve lisans notu korunarak serbestçe kullanılabilir, değiştirilebilir ve dağıtılabilir. Kullanılan kütüphaneler kendi lisanslarındadır (Apache 2.0).

Arka plan ajan görevleri, alıntı doğrulamalı derin araştırma, ajan profilleri, rutin motoru, sessiz saatler, kalıcı hafıza, geri alma, çalışma anında öz-bilgi ve komuta merkezi fikirleri FatihMakes'in **MARK LVII** masaüstü asistanından esinlenmiştir. O projenin kaynak kodu burada yer almaz; bu, bağımsız bir Kotlin/Android uygulamasıdır. (MARK LVII'nin kendisi CC BY-NC 4.0 lisanslıdır: ondan kod ya da dosya alınırsa o lisansın ticari olmayan kullanım koşulu o parçalar için geçerli olur.)

Kod üzerinde çalışan ajanlar ve katkıcılar için kurallar: `AGENTS.md`.
