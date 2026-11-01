# FK JARVIS — Claude Cloud + Local Windows Agent (Starter)

Bu paket, Claude'u bulut tarafında planlayıcı; Windows bilgisayarını ise Photoshop/CapCut/Premiere gibi uygulamaları çalıştıran yerel uç olarak konumlandıran başlangıç mimarisidir.

> Bu bir üretim sürümü değildir. Claude planlama bağlantısı, yerel servis, onay kuyruğu, uygulama başlatma ve güvenli temel uç noktalar iskelet olarak sunulur. Tam Claude Computer Use döngüsü, Photoshop UXP eklentisi, CapCut/Premiere otomasyonları, WhatsApp ve YouTube API bağlantıları ayrıca geliştirilip gerçek hesaplarla test edilmelidir.

## Mimari

- `app/server.py`: Sadece `127.0.0.1` üzerinde çalışan FastAPI yerel köprü.
- `app/claude_client.py`: Anthropic Messages API üzerinden görev planı alır. API anahtarı sadece yerel `.env` dosyasında tutulur.
- `app/approvals.py`: Dış dünyaya etki eden veya bilgisayarda işlem yapan görevler için onay kuyruğu.
- `app/desktop.py`: İzin verilen masaüstü işlemleri (uygulama başlatma, ekran görüntüsü alma). Serbest shell komutu çalıştırmaz.
- `app/ui.py`: PySide6 masaüstü kontrol paneli.
- `app/main.py`: Tek tık başlatıcı; yerel köprüyü arka planda açar, ardından arayüzü gösterir.
- `app/doctor.py`: Kurulum kontrolü (`python -m app.doctor`).
- `app/gemini_voice.py`: Gemini Live API ile gerçek zamanlı mikrofon girişi, Türkçe sesli yanıt, konuşma transkripsiyonu ve hoparlör çıkışı.
- `config/apps.example.json`: Uygulama yolları.

## Kurulum (Windows 10/11)

Ayrıntılı Windows 11 adımları için [README.md](README.md) dosyasına bakın. Kısaca:

1. `setup_windows.bat` dosyasını çalıştır (Python yoksa winget ile kurmayı teklif eder; `.env` ve `config/apps.json` otomatik oluşturulur).
2. `.env` dosyasına `ANTHROPIC_API_KEY` (ve isteğe bağlı `GEMINI_API_KEY`) yaz.
3. Uygulama yolları otomatik bulunamazsa `config/apps.json` dosyasını düzenle.
4. `run_windows.bat` veya masaüstündeki **FK JARVIS** kısayolu ile başlat (köprü + arayüz tek süreçte açılır).

İlk açılışta servis `http://127.0.0.1:8765/health` adresinde yerel olarak dinler. Ağdaki diğer cihazlara açılmaz.

## Kullanım

- Görev alanına ör. `Photoshop'u aç ve ürün fotoğrafı düzenleme için hazırla` yaz.
- `Gemini ile konuşmayı başlat` düğmesiyle mikrofondan Türkçe konuş; Gemini yanıtını hoparlörden dinle. Kullanıcı ve asistan transkripsiyonları görev ekranına eklenir. Düğmeyle görüşmeyi durdurabilirsin.
- Claude görev için bir plan üretir.
- Uygulama başlatma gibi işlemler onay kuyruğuna eklenir.
- Onay verirsen yerel servis yalnızca tanımlı uygulamayı başlatır.
- Harici mesaj gönderme, yayınlama, satın alma, reklam bütçesi değiştirme ve form gönderme özellikleri henüz etkin değildir.

## Gemini sesli görüşme notları

- Ses, yerel Windows mikrofonundan Gemini Live API'ye gerçek zamanlı aktarılır; Gemini yanıtı yerel hoparlörden oynatılır.
- Live API oturum modeli ve ses adı `.env` üzerinden yapılandırılır. Google tarafındaki model erişimi, kota, ücretlendirme ve kullanılabilirlik Google AI Studio / Gemini API hesabına bağlıdır.
- İlk kullanımda Windows mikrofon izni ver. Görüşme sırasında mikrofon açık kalır; işin bitince durdur düğmesine bas.
- Bu entegrasyon canlı ses oturumudur; Gemini sesli görüşmesi, Claude planlayıcıdan ayrı bir ses sağlayıcısıdır. İleriki aşamada Gemini transkripsiyonu ana görev yöneticisine aktarılabilir.

## Güvenlik sınırları

- API anahtarını kaynak koda yazma; `.env` dosyasını paylaşma.
- Servis yalnızca loopback (`127.0.0.1`) üzerinde çalışır.
- Her eylem allow-list üzerinden kontrol edilir.
- Uygulama başlatma ve diğer yerel eylemler onay ister.
- Bu başlangıç sürümünde keyfi terminal komutu, dosya silme, otomatik mesaj gönderme veya ücretli işlem aracı yoktur.
- Claude Computer Use üretim entegrasyonunda ekran görüntüsü, tool_use yanıtı, yerel uygulama eylemi ve tool_result döngüsü doğru API sürümüyle uygulanmalıdır. Ekrandaki talimatlar güvenilir komut kabul edilmemelidir.

## Sonraki geliştirme aşamaları

1. Anthropic Computer Use tool loop (model ve tool sürümünü Anthropic'in güncel dokümantasyonundan sabitle).
2. Yerel ekran yakalama + mouse/keyboard yürütücüsü; her eylem tipine ayrı izin ve durdurma tuşu.
3. Photoshop UXP paneli ve kontrollü işlem komutları.
4. Premiere Pro için desteklenen scripting/extension yüzeyi; CapCut için yalnızca test edilmiş UI otomasyonu.
5. WhatsApp Business Cloud API ve YouTube Data API; token kasası, webhook, yayın/gönderim onayı.
6. İş geçmişi, görev dosyaları, çıktı kontrolü ve geri alma/versiyonlama.

## Test

```powershell
.venv\Scripts\python.exe -m pytest
```

veya `test_windows.bat`.

## Claude Code'a devam ettirme

`docs/CLAUDE_CODE_BRIEF_TR.md` dosyasını Claude Code'a ver. Önce bu starter'ı incelemesini, testleri çalıştırmasını ve eksikleri aşama aşama tamamlamasını iste.
