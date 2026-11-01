# FK JARVIS OS — Aşamalar ve yol haritası

Gereksinim kaynağı: [CLAUDE_CODE_BRIEF_TR.md](CLAUDE_CODE_BRIEF_TR.md) (8 aşamalı plan) ve
[ARCHITECTURE_TR.md](ARCHITECTURE_TR.md) (modül yol haritası). Her aşama ayrı bir commit'tir ve testleri
geçmeden kapatılmaz.

## Tamamlanan aşamalar

| # | Aşama | Kapsam | Doğrulama |
|---|---|---|---|
| 0 | Windows 11'de çalışır hale getirme | Kurulum/başlatma betikleri, `.env` yükleme, Gemini çok turlu görüşme, Türkçe "İ" düzeltmesi, uygulama yolu keşfi | Birim testleri, ekransız arayüz |
| 0b | Asistan becerileri ve arayüz | Sohbet + 30 beceri, Computer Use döngüsü, bağlayıcılar, tasarım paketi | Sahte istemci testleri, sanal ekranda yürütücü |
| **1** | **Veritabanı** | Tek SQLite, sürümlü atomik göçler, eski dosyaların içe aktarımı, kalıcı onaylar (çift karar yok), hash zincirli denetim kaydı, cihaz kimliği | `test_db.py` (göç, geri alma, kurcalama tespiti, eşzamanlı onay yarışı) |
| **2** | **Arka uç** | Görev servisi + durum makinesi, orkestratör, politika motoru, acil durdurma, maliyet/bütçe, çalışma klasörleri, `/api/v1`, jetonlu kimlik doğrulama | `test_backend.py` + canlı köprüye HTTP kontrolü |
| **3** | **Kontrol paneli** | Canlı metrikler, ACİL DURDUR, Görev Panosu (zaman çizelgesi), denetim görünümü, bütçe ve çalışma klasörü ayarları | Ekransız arayüz canlı köprüyle: görev → ajan → başarısız; acil durdurma → görevler iptal |
| **4** | **Belgeler** | [MIMARI.md](MIMARI.md), [API.md](API.md), [ENTEGRASYONLAR.md](ENTEGRASYONLAR.md), bu belge, `CLAUDE.md`, bakım komutları (`python -m app.db info/backup/verify`) | — |

## Sıradaki aşamalar (öneri sırası)

| # | Aşama | Brif/mimari karşılığı | Notlar |
|---|---|---|---|
| 5 | **Gizli anahtar kasası** | Brif §6: "Token'ları Windows Credential Manager… içinde sakla" | `keyring` ile API anahtarları ve WhatsApp/YouTube jetonları Credential Manager'a; `.env`'de yalnız seçenekler kalsın; geçiş göçü |
| 6 | **Çıktı doğrulama ve sürümleme** | Brif §7: "dosya sürümleme, çıktı doğrulama, hata kurtarma" | Ajan/Photoshop çıktılarının görev kaydına eklenmesi, üzerine yazmadan önce kopya, beklenen dosyanın var olduğunun kontrolü |
| 7 | **Photoshop UXP paneli** | Brif §4 | Photoshop içinde panel; köprüye `X-Jarvis-Token` ile bağlanır; katman/belge bilgisi ve geri alınabilir işlemler. Mevcut COM komutları yedek olarak kalır |
| 8 | **Premiere / CapCut** | Brif §5 | Premiere için ExtendScript/UXP araştırması; CapCut için yalnız Computer Use (deneysel olarak etiketli) |
| 9 | **Android eşleştirme** | Mimari: Android 15/16 | QR ile eşleştirme: cihaz başına ayrı jeton, yalnız `/api/v1` alt kümesi (görev oluştur, onay ver/reddet, panel). Yerel ağ yerine aşağıdaki relay tercih edilir |
| 10 | **Bulut relay (outbound TLS)** | Mimari: "yerel ajan buluta doğru TLS bağlantısı başlatır" | Bilgisayardan dışarı açılan WebSocket; gelen port açılmaz; kısa ömürlü cihaz jetonu |
| 11 | **Telemetri ve hata kurtarma** | Brif §7 | Yapılandırılmış günlük (JSON), ajan adım sınırı/zaman aşımı ayarları. (Yapıldı: açılışta yarım kalan `running`/`planning` görevler `failed` yapılır — `tasks.recover_interrupted`) |
| 12 | ~~Kaldırma ve EXE testi~~ **yapıldı** | Brif teslimleri | Inno Setup kurulum/kaldırma + PyInstaller paketi her commit'te Windows CI'da kurulup sınanıyor. Kalan: gerçek kullanıcı masaüstünde (mikrofon, ekran, Photoshop) el ile deneme |
| 13 | **3B avatar** | Mimari modül tablosu | Qt Quick 3D; şimdilik 2B "Holographic Core" |

## Bilinen sınırlar

- Windows'a özgü kodun (kurulum betikleri, Photoshop COM, DPI, ekran görüntüsünden gizleme, SAPI ses, bildirimler)
  hiçbiri gerçek Windows'ta çalıştırılmadı. Ayrıntı: [ENTEGRASYONLAR.md](ENTEGRASYONLAR.md).
- Geçerli Claude/Gemini anahtarıyla uçtan uca yanıt alınmadı.
- Görevler tek kullanıcılıdır; "kullanıcı kimliği" yerine cihaz kimliği vardır (mimari belgede çok kullanıcı istenmiyor).
- Acil durdurma, çalışan bir fare/klavye eylemini ortasında kesmez; bir sonraki adımda durdurur. Anında kesme için
  fareyi ekranın sol üst köşesine götürün (pyautogui güvenlik kilidi).
