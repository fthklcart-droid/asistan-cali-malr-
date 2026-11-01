# FK JARVIS – Arayüz ve Tema

## Görsel sistem
- Ana zemin: gece laciverti / siyaha yakın (#060B14)
- Panel: koyu mavi (#0D192A)
- Ana vurgu: neon camgöbeği (#38BDF8)
- İkincil vurgu: holografik mor (#8B5CF6)
- Yazı: soğuk beyaz (#EAF4FF), ikincil metin: mavi-gri
- Köşeler: 12–22 dp/px, ince mavi kenarlıklar, geniş boşluklar

## Windows masaüstü
`app/ui.py` PySide6 kontrol merkezidir. Sol gezinme alanı, holografik çekirdek paneli, servis kartları, görev alanı, ses kontrolü ve bekleyen onaylar içerir. Var olan yerel HTTP köprüsüne (`127.0.0.1:8765`) bağlanır.

## Android 15/16
`android/FK_JARVIS_Android_15_16` içindeki Jetpack Compose ekranı aynı renk sistemini taşır. Genel / Görevler / Onaylar sekmeleri ve Claude / Gemini seçim kontrolleri arayüz prototipidir.

## Gerçek işlev sınırı
Arayüz kodu projeye eklendi. Ancak mobil ekrandaki görev gönderme ve mikrofon kontrolleri şu an prototip durumundadır; backend bağlantısı ve Gemini Live oturumu kurulmadan gerçek görev veya ses aktarımı yapmaz. Windows ses düğmeleri mevcut Python ses modüllerini çağırır. Claude, Gemini ve diğer servisler için API anahtarları `.env` dosyasına girilmeli ve ilgili servisler ayrıca yapılandırılmalıdır.

## Uygulama notu (masaüstü)
Tasarım, mevcut tüm işlevlerle birleştirildi: sol menüdeki her düğme gerçek bir sayfaya gider
(Genel Bakış, Asistan Merkezi, Sesli Komutlar, Kreatif Stüdyo, Sosyal Medya, Görev Onayları, Planlayıcı,
Geçmiş, Ayarlar). Servis kartları sabit metin değil; köprü ve `/status` uç noktasından gelen gerçek durumu
gösterir (ör. "● HAZIR", "● API ANAHTARI GEREKLİ", "● 3/5 uygulama bulundu"). Android kartı, mobil
uygulama henüz masaüstüne eşleştirilmediği için "PROTOTİP" olarak görünür.
