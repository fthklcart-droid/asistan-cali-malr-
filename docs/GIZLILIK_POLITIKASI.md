# SRİUS JARVIS — Gizlilik Politikası

Son güncelleme: 9 Kasım 2026

SRİUS JARVIS ("uygulama"), Android için Türkçe sesli kişisel asistandır. Bu politika hangi verilerin nasıl işlendiğini açıklar.
*(Yayınlamadan önce aşağıdaki iletişim bilgisini kendi bilginle değiştir.)*

## 1. İşlenen veriler

| Veri | Ne zaman | Nereye gider | Saklama |
|---|---|---|---|
| **Ses (mikrofon)** | Yalnızca KONUŞ'a bastığında, asistan soru sorduktan sonra ya da kullanıcı açtıysa "JARVIS" uyandırma sözcüğü için | Telefondaki konuşma tanıma hizmetine (çoğunlukla Google) metne çevrilmek üzere | Uygulama ses kaydı **saklamaz** |
| **Konuşma metni** | Telefonun kendi anlayamadığı isteklerde | Kullanıcının seçtiği yapay zekâ sağlayıcısına: Google Gemini, OpenAI, Anthropic Claude ya da kullanıcının kendi sunucusu. Yalnızca söylenen cümle, son 6 konuşma satırı ve saat bilgisi gönderilir | Cihazda yalnızca metin geçmişi; Ayarlar'dan silinebilir ya da kapatılabilir |
| **Takvim** | Yalnızca "bugün ne yapmam gerekiyor" sorulduğunda, izin istenerek | **Hiçbir yere** gönderilmez; yalnızca sesli okunur | Saklanmaz |
| **Ekran içeriği** (erişilebilirlik hizmeti) | Yalnızca kullanıcı hizmeti açtıysa ve bir ekran komutu verdiğinde, o anki ekran | **Hiçbir yere** gönderilmez | Saklanmaz |
| **API anahtarları** | Kullanıcı girdiğinde | Yalnızca ilgili sağlayıcıya, istek başlığında | Cihazda Android Keystore ile şifreli |
| **Pano** | Kullanıcı "kopyala" dediğinde | Telefonun panosu | Android tarafından yönetilir |

Uygulama reklam, analiz ya da izleme kitaplığı içermez. Konum, kişiler, SMS, arama kaydı, fotoğraf ve dosyalara erişmez. Parolalar kaydedilmez ve iletilmez.

## 2. Üçüncü taraf hizmetler
Yapay zekâ sağlayıcısı seçildiğinde, gönderilen metin o sağlayıcının kendi gizlilik koşullarına tabidir:
Google (https://policies.google.com/privacy), OpenAI (https://openai.com/policies/privacy-policy), Anthropic (https://www.anthropic.com/legal/privacy).
"Yalnızca cihaz içi" seçeneğinde hiçbir metin dışarı gönderilmez.

## 3. Güvenlik
Tüm bulut bağlantıları HTTPS ile şifrelenir. API anahtarları cihazda donanım destekli Keystore ile şifrelenir. Mesaj gönderme, paylaşım, ödeme, silme gibi sonuç doğuran işlemler kullanıcının açık onayı olmadan yapılmaz.

## 4. Kullanıcı kontrolü
- Mikrofon, takvim ve erişilebilirlik izinleri Android Ayarları'ndan her an geri alınabilir.
- Konuşma geçmişi Ayarlar > Geçmiş ve gizlilik > Geçmişi temizle ile silinir.
- Uygulamayı kaldırmak tüm yerel verileri siler.

## 5. Çocuklar
Uygulama 13 yaş altı çocuklara yönelik değildir.

## 6. İletişim
Geliştirici: **[ADINIZ / ŞİRKETİNİZ]** — E-posta: **[E-POSTA ADRESİNİZ]**
