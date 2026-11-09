# Google Play'e yükleme ve Play Protect / sertifika kabulü

Bu proje Google Play'in teknik şartlarına göre ayarlandı. Aşağıdaki adımların bir kısmını **senin** yapman gerekir: imza anahtarı, geliştirici hesabı, Play Console formları.

## Projede hazır olanlar
| Şart | Durum |
|---|---|
| Hedef API: Android 16 (targetSdk 36), Play'in güncel alt sınırının üstünde | ✔ |
| Android App Bundle (`.aab`) çıktısı | ✔ `bundleTamRelease`, `bundleBasitRelease` (CI'da otomatik) |
| Hata ayıklanamaz release, v1+v2+v3 imza | ✔ |
| Her derlemede artan `versionCode` (CI çalıştırma numarası) | ✔ |
| Kendi paket adın: `./gradlew bundleTamRelease -PappId=com.seninadin.sirius` | ✔ |
| Erişilebilirlik için uygulama içi açık bilgilendirme ve onay ekranı | ✔ (Ayarlar > Ekran kontrolü > AYARLARI AÇ) |
| İlk açılışta gizlilik ve izin bilgilendirmesi; onaysız mikrofon kullanılmaz | ✔ |
| Takvim izni öncesi açıklama ekranı | ✔ |
| Ön plan hizmeti türü `microphone`, kullanıcı başlatır, bildirim görünür | ✔ |
| Gizlilik politikası metni | ✔ `docs/GIZLILIK_POLITIKASI.md` (iletişim bilgisini doldur, herkese açık bir adreste yayınla) |
| Erişilebilirlik içermeyen sürüm (Play incelemesi ya da Play Protect sorun çıkarırsa) | ✔ `basit` sürüm |

## 1) Kendi imza anahtarını oluştur (zorunlu)
Depodaki `signing/sirius-test.jks` herkese açık bir **test** anahtarıdır; Play'e ya da geliştirici doğrulamasına **bununla gitme**.
```bash
./scripts/create_release_keystore.sh signing/release.jks sirius-release
```
Anahtar dosyasını ve şifreleri güvenli bir yerde yedekle; kaybedersen güncelleme yayınlayamazsın.

**GitHub Actions'ta kendi anahtarınla otomatik imzalama:** Depo → Settings → Secrets and variables → Actions → şu sırları ekle:
- `SRIUS_KEYSTORE_BASE64` = `base64 -w0 signing/release.jks` çıktısı
- `SRIUS_STORE_PASSWORD`, `SRIUS_KEY_ALIAS`, `SRIUS_KEY_PASSWORD`

Bundan sonra her derleme AAB ve APK'ları senin anahtarınla imzalar. CI günlüğünde "İmza sertifikası" altında **SHA-256 parmak izi** yazar.

## 2) Google Play Console
1. https://play.google.com/console — geliştirici hesabı aç (tek seferlik ücret, kimlik doğrulaması).
2. **Uygulama oluştur** → `SRIUS_JARVIS_tam.aab` (ya da `basit`) dosyasını önce **Dahili test** kanalına yükle.
3. **Play Uygulama İmzalama**yı kabul et; senin anahtarın "yükleme anahtarı" olur.
4. **Uygulama içeriği** bölümü:
   - **Gizlilik politikası URL'si:** `docs/GIZLILIK_POLITIKASI.md`'yi yayınladığın adres (ör. GitHub Pages).
   - **Veri güvenliği formu:**
     - "Ses kayıtları": toplanmaz, yalnızca cihazda işlenir.
     - "Uygulama etkileşimleri / kullanıcı içeriği (sohbet metni)": üçüncü taraf yapay zekâ sağlayıcısıyla paylaşılır, isteğe bağlı, aktarımda şifreli, kullanıcı silebilir.
     - Konum, kişiler, finans: toplanmaz.
   - **Erişilebilirlik API'si beyanı:** "Uygulama bir erişilebilirlik aracı değil; erişilebilirliği sesli komutla telefon kontrolü için kullanıyor". Uygulama içi bilgilendirme ekranını gösteren kısa bir video gerekir. Bu beyan reddedilirse **basit** sürümü yayınla.
   - **Ön plan hizmeti beyanı (FOREGROUND_SERVICE_MICROPHONE):** kullanım durumu "kullanıcının başlattığı sesli asistan oturumu / uyandırma sözcüğü". Bildirimi gösteren kısa bir video iste.
   - İçerik derecelendirme anketi, hedef kitle (13+), reklam yok.
5. Dahili testten sonra kapalı test → üretim.

## 3) Play dışında yükleme (APK) ve Play Protect
- Play Protect, bilinmeyen geliştiricilerin erişilebilirlik ve mikrofon isteyen uygulamalarını uyarabilir ya da engelleyebilir. **Kendi anahtarınla** imzalanmış ve Play Console'da kayıtlı bir uygulama bu uyarıları büyük ölçüde almaz.
- Google'ın **Android geliştirici doğrulaması** kuralı ülke ülke devreye giriyor: Play dışından kurulan uygulamaların da doğrulanmış bir geliştiriciye kayıtlı olması gerekiyor. Bunun için Android Developer Console'da kimliğini doğrulayıp **paket adını** ve imza sertifikasının **SHA-256 parmak izini** kaydet (parmak izi CI günlüğünde).
- Test anahtarıyla imzalı APK'lar yalnızca kişisel denemeler içindir.

## Uygulama tarafında doğrulanamayanlar
Play incelemesinin sonucu, Play Protect kararları ve geliştirici doğrulaması Google'a bağlıdır; bu depoda test edilemez.
