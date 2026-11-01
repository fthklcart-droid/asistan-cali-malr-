# FK JARVIS — Entegrasyonlar

Her dış servis ve işletim sistemi bağlantısı için: ne için kullanıldığı, nasıl yapılandırıldığı, hangi verinin
gönderildiği, hangi güvenlik kapısından geçtiği ve **nasıl test edildiği**.

Test durumu etiketleri:
- **Canlı** — gerçek servise karşı çalıştırıldı.
- **Kısmi canlı** — gerçek servise ulaşıldı ama yalnız bir yol (ör. kimlik hatası) denendi.
- **Sahte** — birim testleri sahte istemci/sunucu ile; gerçek servis bu ortamdan denenmedi.
- **Denenmedi** — yalnız kod incelemesi; Windows veya hesap gerektirir.

Geliştirme ortamı bir Linux bulut kapsayıcısıdır; bazı sunuculara ağ politikası nedeniyle erişilemez.
Ek olarak her commit'te **GitHub Actions `windows-latest`** üzerinde (gerçek Windows Server, Python 3.12):
tüm testler, `app.doctor`, PyInstaller paketi, paketlenmiş `.exe --selftest`, Inno Setup kurulumunun sessiz
kurulumu + kurulu `.exe --selftest` + sessiz kaldırma çalıştırılır (`.github/workflows/windows.yml`).
"Windows CI" etiketi bu ortamda doğrulandığını belirtir (masaüstü oturumu olmadan; fare/ses gibi donanım yok).

---

## 1. Anthropic Claude API

| | |
|---|---|
| Kullanım | Görev planı (`claude_client.py`), sohbet (`sohbet.py`), bilgisayar kontrolü (`computer_agent.py`), görsel/fatura (`beceriler/gorsel.py`) |
| SDK | `anthropic` Python SDK (≥ 0.125), `max_retries=3` (408/409/429/5xx ve bağlantı hatalarında üstel bekleme) |
| Model | `.env › ANTHROPIC_MODEL`, boşsa `claude-opus-5-5` |
| Yapılandırma | `.env › ANTHROPIC_API_KEY` (Ayarlar sayfasından da girilir) |
| Gönderilen veri | Görev metni, sohbet geçmişi, araç sonuçları; bilgisayar kontrolünde **ekran görüntüleri**; görsel/fatura için seçilen resim |
| Güvenlik kapısı | Her çağrıdan önce `policy.evaluate_model_call`: acil durdurma ve günlük bütçe |
| Maliyet kaydı | Her yanıtın `usage` alanı → `usage` tablosu (girdi/çıktı/önbellek token, web araması, tahmini USD) |

Kullanılan API özellikleri:

| Özellik | Nerede | Not |
|---|---|---|
| Messages API (tek çağrı) | Plan | `output_config.effort = medium` |
| Sunucu tarafı yedek model | Plan | `betas=["server-side-fallback-2026-07-01"]`, `fallbacks="default"`; 400 alınırsa sade istekle tekrar |
| Akış (streaming) | Sohbet | `messages.stream`, kelime kelime arayüze |
| Web araması / sayfa okuma (sunucu araçları) | Sohbet | `web_search_20260209`, `web_fetch_20260209` (en fazla 5'er); `pause_turn` devam ettirilir |
| İstem önbelleği | Sohbet | Üst düzey `cache_control: ephemeral` |
| İstemci araçları | Sohbet | 30 beceri (`beceriler.TOOLS`), `eager_input_streaming` |
| Computer Use | Bilgisayar kontrolü | `computer_toolset_20260801` + `ask_user` aracı; her `tool_result` `toolset_name: "computer"` taşır |
| İlerleme notları | Bilgisayar kontrolü | `thinking.display = "updates"` (beta `thinking-display-updates-2026-08-18`); desteklenmezse kapatılıp devam edilir |
| Yapılandırılmış çıktı | Fatura okuma | `messages.parse(output_format=Fatura)` (pydantic) |
| Ret (`refusal`) | Hepsi | Kullanıcıya Türkçe açıklama; araç çalıştırılmaz |

Test durumu: **Kısmi canlı** — gerçek API'ye geçersiz anahtarla istek gönderildi; 401 → görev `failed`, Türkçe
hata (canlı köprü üzerinden doğrulandı). Geçerli anahtarla yanıt **denenmedi** (ortamda anahtar yok).
Döngü mantığı (araç sonuçları, `pause_turn`, ret, hata geri alma, `toolset_name`) **sahte** istemcilerle test edildi
(`test_sohbet.py`, `test_computer_agent.py`).

## 2. Google Gemini Live API (ses)

| | |
|---|---|
| Kullanım | Gerçek zamanlı Türkçe sesli görüşme (`gemini_voice.py`) |
| SDK | `google-genai` (`client.aio.live.connect`) |
| Yapılandırma | `GEMINI_API_KEY`, `GEMINI_LIVE_MODEL` (boşsa sırayla `gemini-3.1-flash-live-preview`, `gemini-2.5-flash-native-audio-preview-12-2025`, `…-09-2025`), `GEMINI_VOICE_NAME` |
| Ses | Mikrofon PCM 16 kHz → Gemini; Gemini PCM 24 kHz → hoparlör (`sounddevice`) |
| İşlev çağrısı | `jarvis_gorev(gorev)`: kullanıcı bilgisayarda iş istediğinde görev metni Kreatif Stüdyo'ya aktarılır; **otomatik çalışmaz** |
| Güvenlik | Gemini hiçbir eylemi doğrudan çalıştıramaz; yalnızca görev metni önerir |

Test durumu: **Sahte** — çok turlu görüşme döngüsü sahte oturumla test edildi (`test_gemini_voice.py`).
Model adları Google belgelerinden doğrulanamadı (belge sitesi bu ortamdan erişilemez).

## 3. Google Web Speech (kısa sesli komut, sohbet dikte)

| | |
|---|---|
| Kullanım | 4–6 sn kayıt → Türkçe metin (`windows_voice_commands.py`) |
| Kütüphane | `SpeechRecognition.recognize_google(language="tr-TR")` (anahtarsız uç nokta) |
| Gönderilen veri | Ses kaydı |
| Akış | Uygulama adı geçiyorsa → onay isteği; geçmiyorsa → görev alanına metin |

Test durumu: **Sahte** (metin eşleştirme ve Türkçe büyük/küçük harf testleri). Ses tanıma **denenmedi**.

## 4. Açık harita ve hava servisleri (anahtarsız)

| Servis | Kullanım | Not |
|---|---|---|
| OpenStreetMap Nominatim | Adres → koordinat | Kullanım kuralı: ~1 istek/sn; hata/429'da Open-Meteo'ya düşer |
| Open-Meteo Geocoding | Yedek yer adı çözümü | |
| Open-Meteo Forecast | Anlık hava + 7 güne kadar tahmin | |
| OSRM (router.project-osrm.org) | Karayolu mesafe/süre | Hata olursa yalnız kuş uçuşu |
| Overpass API | Yakındaki eczane, ATM vb. | En fazla 10 sonuç |

Gönderilen veri: yer adları/koordinatlar. Test durumu: **Sahte** — bu ortamın ağ politikası bu sunuculara
erişimi engelliyor; yedekleme davranışı sahte istemciyle test edildi.

## 5. WhatsApp Business Cloud API (Meta)

| | |
|---|---|
| Kullanım | Metin mesajı gönderme (`connectors/whatsapp.py`) |
| Uç nokta | `POST https://graph.facebook.com/{WHATSAPP_API_VERSION, varsayılan v23.0}/{WHATSAPP_PHONE_NUMBER_ID}/messages` |
| Yapılandırma | `WHATSAPP_TOKEN` (Bearer), `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_API_VERSION` — Meta for Developers › WhatsApp › API Setup |
| Numara biçimi | `0532…`, `+90 532…`, `532…` → `90532…` |
| Güvenlik | Risk: `external`; her mesaj onaylı; günlük sınır 50 (`settings › policy.limit.whatsapp_send`) |
| Not | Meta kuralı: kullanıcı son 24 saatte yazmadıysa serbest metin yerine onaylı şablon gerekir (şablon desteği yok) |

Test durumu: **Sahte** (istek gövdesi, başlıklar, hata eşlemesi). Gerçek hesapla **denenmedi**.

## 6. YouTube Data API v3

| | |
|---|---|
| Kullanım | Video yükleme (`connectors/youtube.py`), varsayılan gizlilik `private` |
| Kimlik | OAuth "Masaüstü uygulaması" istemcisi; `YOUTUBE_CLIENT_SECRET_FILE`; ilk yüklemede tarayıcıda izin; jeton `data/youtube_token.json` |
| Kapsam | `https://www.googleapis.com/auth/youtube.upload` |
| Yükleme | Devam ettirilebilir (8 MB parçalar) |
| Güvenlik | Risk: `external`; herkese açık yüklemede uyarı; günlük sınır 10 |

Test durumu: **Sahte** (doğrulama kuralları). Gerçek hesapla **denenmedi**.

## 7. Adobe Photoshop (Windows COM + ExtendScript)

| | |
|---|---|
| Kullanım | Sabit güvenli komutlar: belge bilgisi, dosya aç, otomatik ton/kontrast, uzun kenara göre boyutlandır, JPEG/PNG dışa aktar |
| Teknik | `pywin32` → `Photoshop.Application` → `DoJavaScript(betik)` |
| Güvenlik | Serbest betik yok; yollar JSON ile kaçışlanır (enjeksiyon testi var); dışa aktarma var olan dosyanın üzerine yazmaz; her komut onaylı |

Test durumu: **Sahte** (üretilen betik). Photoshop ile **denenmedi** (Windows + Photoshop gerekir).
UXP eklentisi henüz yok — bkz. [YOL_HARITASI.md](YOL_HARITASI.md).

## 8. Windows işletim sistemi

| Entegrasyon | Modül | Test durumu |
|---|---|---|
| Uygulama başlatma (`DETACHED_PROCESS`), Adobe/CapCut/Chrome/Edge yol keşfi | `desktop.py` | Yol çözümleme: sahte dosyalarla. Başlatma: denenmedi |
| Ekran görüntüsü (`mss`), fare/klavye (`pyautogui`), pano ile Türkçe yazma (`pyperclip`) | `executor.py` | **Canlı (Linux sanal ekran)**: 2560×1440 ekranda ölçekleme, tıklama, `Şeker çığ öğüt @ "İstanbul"` yazımı, kısayol, yakınlaştırma. Windows'ta denenmedi |
| DPI farkındalığı (`SetProcessDpiAwareness`) | `executor.py` | Denenmedi |
| Onay panelini ekran görüntüsünden gizleme (`SetWindowDisplayAffinity`, `WDA_EXCLUDEFROMCAPTURE`) | `agent_overlay.py` | Denenmedi |
| Acil durdurma kısayolu Ctrl+Alt+F12 (`pynput`) | `agent_worker.py` | Denenmedi |
| Bilinen klasörler (`SHGetKnownFolderPath`, OneDrive uyumlu) | `workspaces.py` | **Windows CI**: gerçek klasör yolları döndü |
| Çevrimdışı Türkçe ses (`pyttsx3` → SAPI5) | `konusma.py` | Metin temizleme test edildi; ses denenmedi |
| Bildirim (sistem tepsisi) | `ui.py` | Denenmedi |
| Masaüstü kısayolu (PowerShell `WScript.Shell`) | `setup_windows.bat` | Denenmedi |
| Paketlenmiş program (PyInstaller) | `packaging/build_windows.py` | **Windows CI**: `.exe --selftest` — 32 modül, veritabanı, köprü (HTTP + jeton), pencere (10 sayfa) |
| Kurulum programı (Inno Setup, kullanıcı başına, yönetici izni yok) | `packaging/fk_jarvis.iss` | **Windows CI**: sessiz kurulum, dosya denetimi, kurulu `.exe --selftest`, sessiz kaldırma |
| COM/pywin32, SAPI sürücüsü, pyautogui, pynput içe aktarma | çeşitli | **Windows CI** (içe aktarma; donanım etkileşimi değil) |

## 9. Yerel köprü API'si (FK JARVIS'in kendisi)

Tüm uç noktalar ve örnekler: [API.md](API.md). Test durumu: **Canlı** — `TestClient` testleri (73) ve gerçek
Uvicorn sunucusuna HTTP ile uçtan uca kontrol (jetonsuz 401, görev oluşturma, panel özeti); arayüz ekransız
modda canlı köprüyle çalıştırıldı (Görev Panosu, acil durdurma, denetim görünümü).
