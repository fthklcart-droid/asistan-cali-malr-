# Claude Code talimatı – FK JARVIS Android istemcisi

Bu Android Studio projesini mevcut FK JARVIS Cloud + Local Windows mimarisiyle entegre et.

1. Android 15/API 35 ve Android 16/API 36 desteğini koru.
2. Backend API sözleşmesini gerçek masaüstü ajanı servisiyle uyumlu hale getir.
3. Claude API anahtarını mobil uygulamaya koyma. Claude çağrıları yalnızca backend üzerinden yapılsın.
4. Gemini Live ses için backend'den kısa ömürlü, kısıtlı oturum bilgisi al; kalıcı Gemini anahtarı APK'da bulunmasın.
5. Türkçe ses girişi/çıkışı, konuşma geçmişi, kesintiye dayanıklılık ve mikrofon izin akışını uygula.
6. Cihaz eşleştirme QR kodu veya tek kullanımlık kod ile yapılsın.
7. Görev durumu, masaüstü bağlantısı, dosya yükleme, bildirimler ve onay/ret ekranlarını ekle.
8. Her uzaktan masaüstü komutunda yetki kapsamı ve onay kontrolü uygula. Mesaj gönderme, yayınlama, ödeme, silme ve dışarıya veri gönderme için açık onay iste.
9. API hatalarında kullanıcıya gerçek durum göster; sahte başarı üretme.
10. Unit/UI testleri, release build, imzalama yönergesi ve Türkçe kurulum belgesi hazırla.
11. Backend yoksa mock servisleri yalnızca debug build'de kullan; release build'de gerçek sunucu gerektir.
