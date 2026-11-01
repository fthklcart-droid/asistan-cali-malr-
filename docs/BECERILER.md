# FK JARVIS — Beceriler ve özellikler

## Menü (sol gezinme çubuğu)

| Sayfa | Ne yapar |
|---|---|
| ⌂ **Genel Bakış** | Holographic Core paneli, canlı servis kartları (Claude, Gemini, Windows ajanı, Android), hızlı komut kutusu, bugünün görevleri ve bekleyen onaylar. Çekirdek, ses görüşmesi veya bilgisayar kontrolü sırasında mor parlar. |
| ◉ **Asistan Merkezi** | Claude ile Türkçe sohbet; yanıt kelime kelime akar. İnternette arama/sayfa okuma ve aşağıdaki becerileri kendisi kullanır. 🎤 ile sesli soru, 🔊 ile sesli yanıt. |
| ◌ **Sesli Komutlar** | Gemini Live sesli görüşme ve kısa Windows sesli komutu; konuşma kaydı. |
| ▣ **Kreatif Stüdyo** | *Claude ile planla* ve *Bilgisayarda yaptır* (Claude ekranı görür, fare/klavye kullanır). Uygulama açma düğmeleri ve Photoshop güvenli komutları. |
| ◎ **Sosyal Medya** | WhatsApp Business mesajı ve YouTube video yükleme. |
| ◈ **Görev Onayları** | Dış dünyaya etki eden her işlem (uygulama açma, WhatsApp, YouTube, Photoshop) burada onay bekler. |
| ▦ **Planlayıcı** | Yapılacaklar, hatırlatmalar (Windows bildirimi + isteğe bağlı sesli) ve JARVIS'in hafızası. |
| ◷ **Geçmiş** | Tüm planlar, sohbetler, bilgisayar görevleri ve eylemler (yerel SQLite). |
| ⚙ **Ayarlar** | API anahtarları, mikrofon/hoparlör seçimi, uygulama yolları — `.env` düzenlemeye gerek yok. |

Görsel tema: [TEMA_VE_ARAYUZ_TR.md](TEMA_VE_ARAYUZ_TR.md).

## Sohbet becerileri (Claude'un çağırabildiği araçlar)

| Beceri | Açıklama | Kaynak / gereksinim |
|---|---|---|
| `web_search`, `web_fetch` | İnternette arama, haber, sayfa okuma | Claude sunucu aracı (anahtar gerekmez) |
| `simdi` | Tarih, gün, saat | Bilgisayar saati |
| `hesapla` | Güvenli hesap makinesi (Türkçe virgül, %18 gibi yüzdeler) | Yerel |
| `hava_durumu` | Anlık hava + 7 güne kadar tahmin | Open-Meteo (anahtarsız) |
| `konum_coz`, `harita_baglantisi` | Adres → koordinat, Google Haritalar bağlantısı | OpenStreetMap Nominatim |
| `mesafe_sure` | Karayolu mesafesi ve süresi | OSRM (anahtarsız) |
| `yakin_yer_ara` | Yakındaki eczane, ATM, benzinlik, market… | OpenStreetMap Overpass |
| `hafiza_kaydet/ara/sil` | Kalıcı hafıza; profil/tercihler sohbet başında otomatik hatırlanır | `data/hafiza.db` |
| `gorev_ekle/listele/durum/sil` | Yapılacaklar listesi | `data/planlayici.db` |
| `hatirlatma_kur/listele/sil`, `gunun_ozeti` | Zamanlı hatırlatma ("+20dk", "18:30", "2026-11-05 09:00") | `data/planlayici.db` |
| `dosya_listele/ara/bilgi/oku` | Kullanıcı klasöründeki dosyalar (salt okunur; `.env`, şifre/anahtar dosyaları, AppData ve sistem klasörleri yasak) | Yerel |
| `not_yaz/oku`, `notlari_listele` | Markdown notlar | `data/notlar/` |
| `gorsel_incele` | Fotoğraf/ekran görüntüsünü açıklar | Claude görüntü anlama |
| `fatura_oku` | Fatura/fiş fotoğrafı → kalem kalem CSV (Excel'de açılır), toplam kontrolü | Claude + yapılandırılmış çıktı → `data/faturalar/` |
| `uygulama_ac_iste` | Uygulama açma isteğini Onaylar'a ekler | Yerel köprü |
| `whatsapp_taslak` | WhatsApp mesajını onaya sunar | WhatsApp Cloud API |
| `bilgisayar_gorevi` | Ekran gerektiren işi "Bilgisayarda yaptır" alanına aktarır | Computer Use |

## basak-ai'den uyarlananlar

Başak projesi (© Casper, 2026) **GNU AGPL-3.0** lisanslıdır. Kodunu bu depoya kopyalamak, FK JARVIS'in
tamamını AGPL-3.0 ile açık kaynak yapmayı gerektirirdi. Bu yüzden **kod kopyalanmadı**; Başak'ın
özellik listesi örnek alınarak FK JARVIS mimarisinde (Claude + yerel köprü + onay kuyruğu) **sıfırdan
yazıldı**.

| Başak özelliği | FK JARVIS karşılığı |
|---|---|
| web_search, haber_ara, zamanli_ara, site_ara, sayfa_oku, derin_oku | Claude `web_search` + `web_fetch` |
| simdi, hesapla | `simdi`, `hesapla` |
| hava_durumu | `hava_durumu` (Open-Meteo) |
| konum_coz, harita_goster, mesafe_sure, mesafe_hesapla, poi_ara, sube_bul | `konum_coz`, `harita_baglantisi`, `mesafe_sure`, `yakin_yer_ara` |
| hafiza_ara + kalıcı hafıza | `hafiza_kaydet/ara/sil` + sistem istemine profil özeti |
| add_task, list_tasks, complete_task, update_task_status | `gorev_ekle/listele/durum/sil` + Planlayıcı sekmesi |
| hatirlatma_kur/listele/sil, get_reminders | `hatirlatma_*`, `gunun_ozeti` + Windows bildirimi |
| read_file, list_files, dosya_bilgi | `dosya_oku/listele/bilgi/ara` (daha sıkı güvenlik listesi) |
| write_file_tool (knowledge/) | `not_yaz/oku` (data/notlar/) |
| image_analyze | `gorsel_incele` |
| fatura_oku → katalog | `fatura_oku` → CSV (Vixrex'e özel katalog adımları alınmadı) |
| ac_uygulama | `uygulama_ac_iste` (onaylı) + Görev & Kontrol |
| sayfa_bakis/tikla/yaz/tus/kaydir (tarayıcı kontrolü) | Claude Computer Use — tüm masaüstü, onaylı |
| Sesli konuşma (Piper TTS) + sesli komut (Whisper) | Windows SAPI sesli yanıt + Google tanıma + Gemini Live |

**Alınmayanlar ve nedeni:** Vixrex katalog/fiyat/yetki belgesi, matris (fikir tablosu), freetools.org,
firma takip, git/GitHub/test çalıştırma araçları, 20 ücretsiz model sağlayıcı zinciri, Vercel web sitesi,
Telegram botu, bulut işçisi ve gelir/reklam sayfaları. Bunlar Başak'a ya da Casper'ın projelerine özgüdür
veya FK JARVIS'in Claude tabanlı mimarisinde karşılığı zaten vardır.

## Güvenlik ilkeleri

- Köprü yalnızca `127.0.0.1` üzerinde dinler; dışarıya port açılmaz.
- Dış etkili her işlem Onaylar listesinden geçer; Claude kendine yetki yazamaz (araç adları kodda sabittir).
- Bilgisayar kontrolünde varsayılan mod her eylemi onaylatır. Acil durdurma: **Ctrl+Alt+F12**, sağ üstteki
  **DURDUR** düğmesi veya fareyi ekranın **sol üst köşesine** götürmek.
- Onay paneli ekran görüntülerinden gizlenir (Windows 10 2004+), Claude onu göremez ve tıklayamaz.
- Ekrandaki/web'deki/dosyalardaki talimatlar güvenilmeyen veri sayılır; gönderme, yayınlama, ödeme, silme
  öncesinde Claude ayrıca sorar.
- Anahtarlar yalnızca yerel `.env` dosyasında; `data/` ve `.env` git'e girmez.

## Henüz yapılmayanlar (dürüst liste)

- **Photoshop UXP paneli** yok; Photoshop, Windows COM + ExtendScript ile sabit güvenli komutlarla
  yönetilir (aç, otomatik ton, boyutlandır, JPEG/PNG dışa aktar). Daha karmaşık düzenlemeler için
  "Bilgisayarda yaptır".
- **Premiere Pro / CapCut** için resmi otomasyon arayüzü kullanılmıyor; bu uygulamalar yalnızca Computer Use
  (ekran üzerinden) ile kontrol edilir ve bu yöntem deneyseldir.
- **Android** uygulaması hâlâ kaynak iskeletidir; masaüstüyle eşleştirme yapılmadı.
- WhatsApp ve YouTube bağlayıcıları gerçek hesaplarla **test edilmedi** (birim testleri sahte sunucuyla).
