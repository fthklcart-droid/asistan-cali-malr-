# FK JARVIS — Windows 11 kurulum ve çalıştırma

Claude (görev planlama) + Gemini Live (Türkçe sesli görüşme) + yerel Windows uygulama başlatıcı.

## Hızlı başlangıç (3 adım)

1. **Projeyi indirin:** GitHub'da **Code → Download ZIP**, ZIP'i örn. `C:\FK_JARVIS` klasörüne çıkarın.
   > Klasörü OneDrive ile eşitlenen bir yere (Masaüstü/Belgeler) koymamanız önerilir; `.venv` klasörü büyüktür.
2. **`setup_windows.bat`** dosyasına çift tıklayın.
   - Python 3.11/3.12/3.13 yoksa **winget ile Python 3.12 kurmayı teklif eder** (kurulumdan sonra dosyayı tekrar çalıştırın).
   - Sanal ortamı (`.venv`) oluşturur, paketleri kurar, `.env` ve `config\apps.json` dosyalarını hazırlar.
   - Masaüstüne **FK JARVIS** kısayolu ekler ve kurulum kontrolünü (`app.doctor`) çalıştırır.
3. Açılan `.env` dosyasına anahtarlarınızı yazıp kaydedin:
   ```
   ANTHROPIC_API_KEY=sk-ant-...        # https://console.anthropic.com
   GEMINI_API_KEY=...                  # https://aistudio.google.com/apikey
   ```

Sonra **masaüstündeki FK JARVIS kısayolu** veya **`run_windows.bat`** ile başlatın. Tek pencere açılır; yerel köprü (`127.0.0.1:8765`) arka planda otomatik başlar.

> Windows SmartScreen "Windows bilgisayarınızı korudu" uyarısı verirse **Ek bilgi → Yine de çalıştır** deyin.

## Uygulama yolları

Photoshop, Premiere, Lightroom, CapCut ve tarayıcı **otomatik aranır** (Adobe'nin 2024/2025/2026 sürümleri dahil; Chrome yoksa Windows 11'deki Edge kullanılır). Bulunamayan uygulama arayüzün üst kısmında listelenir. O zaman `config\apps.json` dosyasına tam yolu yazın, örn.:

```json
{ "photoshop": "C:/Program Files/Adobe/Adobe Photoshop 2026/Photoshop.exe" }
```

`%LOCALAPPDATA%`, `%ProgramFiles%` gibi değişkenler kullanılabilir.

## Mikrofon

Sesli özellikler için: **Ayarlar → Gizlilik ve güvenlik → Mikrofon** → "Masaüstü uygulamalarının mikrofonunuza erişmesine izin verin" **Açık** olmalı. Gemini görüşmesinde yankı olmaması için kulaklık önerilir.

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| Herhangi bir sorun | `test_windows.bat` çalıştırın: kurulum kontrolü + testler |
| "Port kullanılıyor" uyarısı | `.env` içinde `JARVIS_PORT=8766` yapın |
| Claude: "anahtar geçersiz" | `.env` içindeki `ANTHROPIC_API_KEY` değerini kontrol edin |
| Gemini bağlanamıyor | `.env` içindeki `GEMINI_LIVE_MODEL` alanına AI Studio'da etkin Live modelin adını yazın |
| Kısayolla açılmıyor | `run_windows.bat` ile açın (konsolda hata görünür) ve `jarvis.log` dosyasına bakın |
| Paket kurulumu hatası | `.venv` klasörünü silip `setup_windows.bat`'ı yeniden çalıştırın |

## Gelişmiş

```bat
.venv\Scripts\python.exe -m app.main      &rem arayüz + köprü
.venv\Scripts\python.exe -m app.server    &rem yalnızca yerel köprü
.venv\Scripts\python.exe -m app.doctor    &rem kurulum kontrolü
.venv\Scripts\python.exe -m pytest        &rem testler
```

Mimari, güvenlik sınırları ve yol haritası: [README_TR.md](README_TR.md), [BASLANGIC_TR.md](BASLANGIC_TR.md), [docs/](docs/). Android kaynakları: `android/`.
