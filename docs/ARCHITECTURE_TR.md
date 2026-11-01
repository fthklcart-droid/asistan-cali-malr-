# FK JARVIS teknik mimari

## Hedef dağıtım

```text
Fatih / FK JARVIS masaüstü arayüzü
             |
             v
Windows Local Agent (127.0.0.1)
  - görev kuyruğu ve onay kapısı
  - uygulama adaptörleri
  - ekran yakalama / bilgisayar yürütücüsü (sonraki faz)
  - Photoshop UXP / Premiere / CapCut adaptörleri
             |
      outbound TLS bağlantısı
             |
             v
Claude Cloud Agent / Orchestrator
  - görevi anlama ve planlama
  - uzman ajan seçimi
  - Computer Use tool-use döngüsü
  - sonuç kontrolü ve raporlama
             |
             v
Bağlayıcılar: WhatsApp Business API / YouTube Data API / web
```

## Neden bu tasarım?

- Büyük medya dosyaları ve lisanslı masaüstü uygulamaları kullanıcının PC'sinde kalır.
- Claude bulutta planlama ve görsel ekran değerlendirmesi yapar; gerçek tıklama/klavye eylemi yerel yürütücüden geçer.
- İnternetten yerel bilgisayara açık port açmak yerine yerel ajan buluta doğru TLS bağlantısı başlatır.
- Harici dünyaya etki eden işlemler (mesaj, yayın, reklam harcaması, form gönderimi) ayrı onay politikalarına bağlanır.

## Görev yaşam döngüsü

1. Kullanıcı Türkçe görev verir.
2. Orchestrator görev türünü sınıflandırır: tasarım, video, web, iletişim, dosya.
3. Gerekli dosyalar ve uygulama durumu belirlenir.
4. Claude bir plan veya Computer Use aracı çağrısı üretir.
5. Yerel ajan eylemi politika motorundan geçirir.
6. Onay gereken eylem kullanıcıya gösterilir; onay gelmeden çalıştırılmaz.
7. Yerel uygulama eylemi gerçekleştirir ve ekran/çıktı geri bildirimini verir.
8. Claude sonucu değerlendirir; gerekirse düzeltme adımı önerir.
9. Dosya çıktısı ve görev raporu kullanıcıya sunulur.

## Güvenlik modeli

- Local API yalnızca loopback üzerinde.
- Bulut bağlantısı için kısa ömürlü cihaz kimliği ve TLS; kalıcı gizli anahtarlar loglanmaz.
- Uygulama allow-list; keyfi shell yok.
- Dosya alanları kullanıcı tarafından seçilen çalışma klasörleriyle sınırlandırılır.
- İndirme/çalıştırma, mesaj gönderme, yayınlama, ödeme, reklam bütçesi ve form gönderme için ayrı onay.
- Ekran görüntüsü ve sayfa içeriği güvenilmeyen girdidir; talimat olarak yürütülmez.
- Acil durdurma: yerel ajan görevini iptal eder, otomasyon döngüsünü keser.

## Modül yol haritası

| Modül | Bu ZIP'teki durum | Üretim için gereken |
|---|---|---|
| Claude planlama API | Başlangıç bağlantısı | Retry, streaming, maliyet/limit yönetimi |
| Yerel Windows köprü | Temel FastAPI servis | Cihaz kimliği, TLS outbound relay, görev kuyruğu |
| Onay kapısı | Bellek içi temel kuyruk | Kalıcı DB, kullanıcı kimliği, denetim kaydı |
| Bilgisayar kullanımı | Henüz yok | Güncel Claude Computer Use tool loop + ekran/klavye yürütücüsü |
| Photoshop | Uygulama başlatma | UXP eklentisi ve işlem komutları |
| Premiere / CapCut | Uygulama başlatma | Program bazlı, test edilmiş adaptörler |
| WhatsApp / YouTube | Henüz yok | Resmî API connector, OAuth/token kasası, onay |
| 3D avatar | Bu starter'da yok | QML/Qt Quick 3D veya ayrı render bileşeni |

Bu tablo, mevcut ZIP'in neleri gerçekten yaptığı ve nelerin sonraki geliştirme olduğunu açıkça ayırır.


## Ses sağlayıcısı: Gemini Live
Gemini Live ayrı bir gerçek zamanlı ses kanalıdır: Windows mikrofonu → PCM 16 kHz → Gemini Live WebSocket → transkripsiyon + PCM 24 kHz ses → Windows hoparlörü. Claude görev planlayıcısı ana akıl/ajan yöneticisi olarak kalır. İleriki entegrasyonda Gemini'nin input transcription metni Claude görev orkestratörüne gönderilir; hiçbir yan etki doğuran araç doğrudan ses modelinin serbest yorumuyla çalıştırılmaz, izin listesi ve onay katmanından geçer.
