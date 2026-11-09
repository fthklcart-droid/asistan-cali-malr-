package com.fk.jarvis.core

/**
 * Cihaz içinde çalışan, internet gerektirmeyen kural tabanlı Türkçe komut sınıflandırıcı.
 * Emin olduğu komutlar için bir [CommandProposal] döndürür; emin değilse null döner
 * ve istek (varsa) LLM sağlayıcısına gider.
 */
class IntentClassifier {

    fun classify(raw: String): CommandProposal? {
        val n = TextNormalizer.normalize(raw)
        if (n.isBlank()) return null
        val s = TextNormalizer.stripApostropheSuffixes(n)

        // 1) Durdurma
        if (n in setOf("dur", "sus", "sessiz ol", "kes", "yeter", "tamam yeter", "iptal", "vazgeç", "konuşmayı kes")) {
            return p(CommandIntent.STOP, spoken = "")
        }
        // 1b) Sohbeti bitirme
        if (n in setOf("görüşürüz", "bu kadar", "teşekkürler bu kadar", "teşekkür ederim bu kadar", "sohbeti bitir", "oturumu kapat", "şimdilik bu kadar")) {
            return p(CommandIntent.END_SESSION)
        }
        // 2) Varlık kontrolü
        if ("duyuyor musun" in n || "orada mısın" in n || "burada mısın" in n) {
            return p(CommandIntent.CHAT, spoken = "Evet, seni duyuyorum.")
        }
        // 3) Tekrar
        if ("tekrar et" in n || "tekrarla" in n || "tekrar söyle" in n) {
            val who = if ("söylediğim" in n || "dediğim" in n) "user" else "assistant"
            return p(CommandIntent.REPEAT_LAST, args = mapOf("who" to who))
        }
        // 4) Ses seviyesi (uygulama açmadan önce: "sesini aç")
        if (Regex("\\bses(i|ini)? ?(kıs|azalt|düşür|alçalt)").containsMatchIn(n) || n == "daha sessiz") {
            return p(CommandIntent.VOLUME_DOWN, spoken = "Sesi kısıyorum.")
        }
        if (Regex("\\bses(i|ini)? ?(aç|yükselt|artır|arttır)").containsMatchIn(n) || n == "daha yüksek sesle") {
            return p(CommandIntent.VOLUME_UP, spoken = "Sesi yükseltiyorum.")
        }
        // 5) Saat / tarih
        if ("saat kaç" in n || n == "saat") return p(CommandIntent.TELL_TIME)
        if ("tarih" in n && ("ne" in n.split(" ") || "kaç" in n || "bugün" in n) ||
            "günlerden ne" in n || "hangi gündeyiz" in n || "bugün ayın kaçı" in n
        ) return p(CommandIntent.TELL_DATE)

        // 5b) Ajanda (cihazdaki takvimden okunur)
        if (Regex("bugün ne(ler)? yapmam gerek|bugünkü (planım|programım|ajandam)|takvimimde (ne|neler) var|ajandam(da)? (ne|neler)|bugün (neler|ne) var").containsMatchIn(n)) {
            return p(CommandIntent.TODAY_AGENDA)
        }
        // 5c) Haberler ve internet araştırması
        if (Regex("haber(ler)?i oku|günlük haber|gündemi (oku|anlat|özetle)|bugünün haberleri|son dakika haber").containsMatchIn(n)) {
            return p(CommandIntent.RESEARCH, args = mapOf("query" to "Bugünün Türkiye ve dünyadan en önemli haberleri"))
        }
        if (n == "internette araştırma yap" || n == "araştırma yap" || n == "internette araştır") {
            return p(CommandIntent.RESEARCH)
        }
        Regex("^(.+?) (?:hakkında|ile ilgili|konusunda) (?:internette )?(?:araştırma yap|araştır|bilgi bul)$").find(s)?.let {
            return p(CommandIntent.RESEARCH, args = mapOf("query" to it.groupValues[1].trim()))
        }
        Regex("^(?:internette )?(.+?) (?:araştır|araştırır mısın)$").find(s)?.let {
            return p(CommandIntent.RESEARCH, args = mapOf("query" to it.groupValues[1].removePrefix("internette ").trim()))
        }
        // 5d) Ekran görüntüsü ve kopyalama
        if ("ekran görüntüsü" in n || "ekran resmi" in n || "screenshot" in n) return p(CommandIntent.SCREENSHOT)
        if (Regex("seçili (metni|yazıyı) kopyala|ekrandaki (seçili )?(metni|yazıyı) kopyala").containsMatchIn(n)) {
            return p(CommandIntent.SCREEN_ACTION, args = mapOf("action" to "copy"))
        }
        Regex("^(?:şunu|şu metni) kopyala (.+)$").find(n)?.let { return p(CommandIntent.COPY_TEXT, args = mapOf("text" to it.groupValues[1].trim())) }
        if (Regex("^(bu metni|bunu|metni|yanıtı|cevabı|son yanıtı|az önceki yanıtı)? ?kopyala$").matches(n) || n == "panoya kopyala") {
            return p(CommandIntent.COPY_TEXT)
        }
        // 5e) YouTube'da arama
        Regex("^youtube'?(?:da|de|ta|te|dan|den) (.+?) (?:ara|arat|bul|aç|oynat)$").find(n)?.let {
            return p(CommandIntent.YOUTUBE_SEARCH, args = mapOf("query" to it.groupValues[1].removeSuffix(" diye").trim()))
        }
        Regex("^(.+?) (?:diye )?youtube (?:ara|arat|bul)$").find(s)?.let {
            return p(CommandIntent.YOUTUBE_SEARCH, args = mapOf("query" to it.groupValues[1].trim()))
        }
        // 5f) Galeri
        if (Regex("\\bgaleri(yi|y)? ?(aç|göster|başlat)|fotoğraflarımı (aç|göster)").containsMatchIn(s)) {
            return p(CommandIntent.OPEN_GALLERY, spoken = "Galeriyi açıyorum.")
        }
        // 5g) Paylaşım ekranı (son yanıtı paylaşır; onay istenir)
        if ("paylaşım ekranını aç" in n || n == "bunu paylaş" || n == "paylaş") return p(CommandIntent.SHARE_TEXT)

        // 6) Ana ekran
        if ("ana ekran" in n || "ana sayfaya dön" in n) return p(CommandIntent.GO_HOME, spoken = "Ana ekrana dönüyorum.")

        // 7) Ekran etkileşimleri (erişilebilirlik hizmeti gerekir)
        screenAction(n, s)?.let { return it }

        // 8) Zamanlayıcı
        if ("zamanlayıcı" in n || "sayaç" in n || "geri sayım" in n) {
            val secs = parseDurationSeconds(n)
            return if (secs != null && secs > 0) p(CommandIntent.SET_TIMER, args = mapOf("seconds" to secs.toString()))
            else p(CommandIntent.CLARIFY, spoken = "Zamanlayıcıyı ne kadar süreye kurayım?")
        }
        // 9) Alarm
        if ("alarm" in n) {
            val hm = parseClock(s)
            return if (hm != null) p(CommandIntent.SET_ALARM, args = mapOf("hour" to hm.first.toString(), "minute" to hm.second.toString()))
            else p(CommandIntent.CLARIFY, spoken = "Alarmı saat kaça kurayım?")
        }
        // 10) Kamera
        if (Regex("\\bkamera(yı|yi)? ?(aç|başlat|çalıştır)").containsMatchIn(s) || "fotoğraf çek" in n) {
            return p(CommandIntent.OPEN_CAMERA, spoken = "Kamerayı açıyorum.")
        }
        // 11) Sistem ayarları
        settingsTarget(n)?.let { return p(CommandIntent.OPEN_SETTINGS, target = it, spoken = "Ayarları açıyorum.") }

        // 12) Telefon numarası çevirme (arama ekranı açılır, arama kullanıcıya bırakılır)
        Regex("(\\+?\\d[\\d ]{6,}\\d)").find(n)?.let { m ->
            if ("ara" in n.split(" ") || "çevir" in n) {
                return p(CommandIntent.DIAL, args = mapOf("number" to m.value.replace(" ", "")))
            }
        }
        // 13) Yol tarifi
        Regex("^(.+?) (?:için )?(?:yol tarifi|navigasyon)").find(s)?.let {
            return p(CommandIntent.NAVIGATE, args = mapOf("query" to it.groupValues[1].trim()))
        }
        Regex("^(.+?) (?:nasıl giderim|götür beni|beni götür)$").find(s)?.let {
            return p(CommandIntent.NAVIGATE, args = mapOf("query" to it.groupValues[1].trim()))
        }
        // 14) Web araması (açık ifade gerektirir; "annemi ara" gibi belirsiz istekler LLM'e gider)
        Regex("^(.+?) (?:diye |hakkında )?(?:google'da|googleda|internette|webde|web'de) (?:ara|arat|arama yap)$").find(n)?.let {
            return p(CommandIntent.WEB_SEARCH, args = mapOf("query" to TextNormalizer.stripApostropheSuffixes(it.groupValues[1]).trim()))
        }
        Regex("^(.+?) diye ara$").find(n)?.let {
            return p(CommandIntent.WEB_SEARCH, args = mapOf("query" to it.groupValues[1].trim()))
        }
        // 15) Bağlantı açma
        Regex("((?:https?://)?(?:www\\.)?[a-z0-9-]+\\.(?:com|net|org|tr|io|dev|app)(?:\\.tr)?(?:/\\S*)?)").find(s)?.let { m ->
            if (Regex("\\b(aç|git|gir)\\b").containsMatchIn(s)) return p(CommandIntent.OPEN_URL, args = mapOf("url" to m.value))
        }
        // 15b) Uygulama kapatma (Android başka uygulamaları kapatmaya izin vermez; arka plana alınır)
        Regex("^(.+?) (?:uygulamasını )?(?:kapat|kapatır mısın)$").find(s)?.let {
            val name = it.groupValues[1].trim()
            if (name !in setOf("ses", "sesi", "wifi", "wi-fi", "bluetooth", "ekran", "ekranı", "alarm", "alarmı", "mikrofon", "mikrofonu")) {
                return p(CommandIntent.CLOSE_APP, args = mapOf("app_name" to name))
            }
        }
        // 16) Uygulama açma
        Regex("^(.+?) (?:uygulamasını |uygulamasini |uygulaması |app )?(?:aç|açar mısın|açabilir misin|açsana|başlat|çalıştır)$").find(s)?.let {
            val name = it.groupValues[1].removeSuffix(" uygulaması").trim()
            if (name.isNotBlank() && name.length <= 40) {
                return p(CommandIntent.OPEN_APP, args = mapOf("app_name" to name))
            }
        }
        return null
    }

    private fun screenAction(n: String, s: String): CommandProposal? {
        fun a(action: String, text: String? = null) = p(
            CommandIntent.SCREEN_ACTION,
            args = buildMap { put("action", action); if (text != null) put("text", text) },
        )
        if (n == "geri" || n == "geri git" || n == "geri dön" || n == "bir önceki ekran") return a("back")
        if ("son uygulamalar" in n) return a("recents")
        if ("bildirim" in n && (" aç" in " $n" || "göster" in n)) return a("notifications")
        if ("aşağı kaydır" in n || "aşağı in" in n) return a("scroll_down")
        if ("yukarı kaydır" in n || "yukarı çık" in n) return a("scroll_up")
        Regex("^(.+?)(?: düğmesine| butonuna| yazısına| seçeneğine)? (?:tıkla|bas|dokun)$").find(s)?.let {
            // Kesme işaretli ekler ("Gönder'e") normalize aşamasında atıldı.
            return a("click", it.groupValues[1].trim())
        }
        Regex("^(?:şunu |bunu )?yaz (.+)$").find(n)?.let { return a("type", it.groupValues[1].trim()) }
        return null
    }

    private fun settingsTarget(n: String): String? {
        val wantsSettings = "ayar" in n || Regex("\\b(aç|göster)\\b").containsMatchIn(n)
        if (!wantsSettings) return null
        return when {
            Regex("wi-?fi|wifi|kablosuz").containsMatchIn(n) -> "wifi"
            "bluetooth" in n -> "bluetooth"
            "parlaklık" in n || "ekran ayar" in n -> "display"
            "ses ayar" in n -> "sound"
            "pil" in n.split(" ") || "batarya" in n -> "battery"
            "konum" in n -> "location"
            "erişilebilirlik" in n -> "accessibility"
            n == "ayarları aç" || n == "ayarlar" || n == "ayarlara git" -> "general"
            else -> null
        }
    }

    companion object {
        private fun p(
            intent: CommandIntent,
            target: String? = null,
            args: Map<String, String> = emptyMap(),
            spoken: String = "",
        ) = CommandProposal(intent, target, args, false, spoken, CommandProposal.Source.LOCAL)

        /** "5 dakika", "1 saat 10 dakika", "otuz saniye" gibi ifadeleri saniyeye çevirir. */
        fun parseDurationSeconds(n: String): Int? {
            val num = "(\\d+|(?:bir|iki|üç|dört|beş|altı|yedi|sekiz|dokuz|on|yirmi|otuz|kırk|elli|altmış)(?: (?:bir|iki|üç|dört|beş|altı|yedi|sekiz|dokuz))?)"
            var total = 0
            var found = false
            for ((unit, mul) in listOf("saat" to 3600, "dakika" to 60, "saniye" to 1)) {
                Regex("$num ?$unit").find(n)?.let { m ->
                    TextNormalizer.parseNumber(m.groupValues[1])?.let { total += it * mul; found = true }
                }
            }
            if ("yarım saat" in n) { total += 1800; found = true }
            if ("buçuk dakika" in n) { total += 30; found = true }
            return if (found) total else null
        }

        /** "7:30", "saat 7", "saat 7 buçukta", "07.15" -> (saat, dakika). */
        fun parseClock(s: String): Pair<Int, Int>? {
            Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(s)?.let { m ->
                val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                if (h in 0..23 && min in 0..59) return h to min
            }
            Regex("saat (\\d{1,2})").find(s)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = if ("buçuk" in s) 30 else 0
                if (("akşam" in s || "öğleden sonra" in s) && h in 1..11) h += 12
                if (h in 0..23) return h to min
            }
            Regex("(\\d{1,2}) ?(?:'?de|'?da|'?te|'?ta|de|da|te|ta)?\\b.*alarm").find(s)?.let { m ->
                var h = m.groupValues[1].toInt()
                if (("akşam" in s) && h in 1..11) h += 12
                if (h in 0..23) return h to (if ("buçuk" in s) 30 else 0)
            }
            return null
        }
    }
}
