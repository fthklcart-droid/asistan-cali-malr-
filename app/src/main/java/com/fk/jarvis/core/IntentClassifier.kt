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

        // 4b) Geri alma (asistanın kendi yaptığı son değişiklik)
        if (n == "geri al" || Regex("^(son )?(yaptığını|yaptığın şeyi|işlemi|değişikliği|eklediğini) geri al$").matches(n) || n == "bunu geri al") {
            return p(CommandIntent.UNDO)
        }
        // 4c) Hafıza
        Regex("^(?:bunu |şunu |lütfen )?(?:hatırla|aklında tut|unutma)[:,]? (.{3,})$").find(n)?.let {
            return p(CommandIntent.REMEMBER, args = mapOf("value" to it.groupValues[1].trim()))
        }
        Regex("^(.{3,}?) (?:olduğunu|diye) (?:hatırla|aklında tut|unutma)$").find(n)?.let {
            return p(CommandIntent.REMEMBER, args = mapOf("value" to it.groupValues[1].trim()))
        }
        if (Regex("benim hakkımda (ne|neler) (biliyorsun|hatırlıyorsun)|(ne|neler) hatırlıyorsun|hafızanda (ne|neler) var|hafızamı göster").containsMatchIn(n)) {
            return p(CommandIntent.RECALL, args = mapOf("query" to ""))
        }
        if (n == "hafızanı sil" || n == "her şeyi unut" || n == "hafızayı temizle") return p(CommandIntent.FORGET, args = mapOf("query" to "*"))
        // 4c') Görevler, notlar, hatırlatıcılar
        Regex("^(?:yeni )?görev(?: ekle| oluştur)?[:,] (.{2,})$|^(?:görev ekle|yapılacaklara ekle|yapılacaklar listesine ekle) (.{2,})$").find(n)?.let {
            return p(CommandIntent.TASK_ADD, args = taskArgs(it.groupValues[1].ifEmpty { it.groupValues[2] }))
        }
        Regex("^(.{2,}?) (?:görevini|görevi|işini) (?:ekle|oluştur)$").find(s)?.let { return p(CommandIntent.TASK_ADD, args = taskArgs(it.groupValues[1])) }
        if (Regex("^(görevlerim|yapılacaklarım|yapılacaklar listem|açık görevlerim)( neler| ne)?$|^(bugünkü|bugün) (görevlerim|yapılacaklarım)( neler| ne)?$|^görevlerimi (oku|göster|say)$").matches(n)) {
            return p(CommandIntent.TASK_LIST, args = if (n.startsWith("bugün")) mapOf("date" to "bugün") else emptyMap())
        }
        Regex("^(.{2,}?) (?:görevini |görevi |işini )?(?:tamamla|tamamlandı|tamamladım|bitti|bitirdim|yapıldı|hallettim)$").find(s)?.let {
            if ("görev" in n || "iş" in n.split(' ').map { w -> w.take(2) }) return p(CommandIntent.TASK_DONE, args = mapOf("query" to it.groupValues[1].removeSuffix(" görevini").removeSuffix(" görevi").trim()))
        }
        Regex("^not(?: al| ekle| et)?[:,]? (.{3,})$").find(n)?.let { return p(CommandIntent.NOTE_ADD, args = mapOf("text" to it.groupValues[1].trim())) }
        Regex("^notlarımda (.{2,}?) (?:ara|bul|var mı)$").find(n)?.let { return p(CommandIntent.NOTE_SEARCH, args = mapOf("query" to it.groupValues[1].trim())) }
        if (n == "notlarımı oku" || n == "notlarım" || n == "son notlarım") return p(CommandIntent.NOTE_SEARCH, args = mapOf("query" to ""))
        if (Regex("^(rutinlerim|hatırlatıcılarım|rutinlerimi göster|hatırlatıcılarımı göster|kurulu hatırlatıcılar)( neler)?$").matches(n)) return p(CommandIntent.ROUTINE_LIST)
        if ("hatırlat" in n) {
            parseClock(s)?.let { (h, m) ->
                val text = n.replace(Regex("\\b(bana |lütfen )?hatırlat(ır mısın|sana|ırsan)?\\b"), " ")
                    .replace(Regex("\\b(her gün|hafta içi|hafta sonu|yarın|bugün|öbür gün|sabah|akşam|öğleden sonra)\\b"), " ")
                    .replace(Regex("saat \\d{1,2}([:.]\\d{2})?( buçuk(ta)?)?('?(de|da|te|ta))?|\\b\\d{1,2}[:.]\\d{2}('?(de|da|te|ta))?"), " ")
                    .replace(Regex("\\s+"), " ").trim().removeSuffix("'i").removeSuffix("'ı").removeSuffix("'u").removeSuffix("'ü").ifBlank { "Hatırlatma" }
                return p(CommandIntent.ROUTINE_ADD, args = buildMap {
                    put("text", text); put("time", "%02d:%02d".format(h, m)); put("action", "remind")
                    when {
                        "her gün" in n -> put("repeat", "her gün")
                        "hafta içi" in n -> put("repeat", "hafta içi")
                        "öbür gün" in n -> put("date", "öbür gün")
                        "yarın" in n -> put("date", "yarın")
                    }
                })
            }
        }
        // 4c") Öz-bilgi: yapabildikleri ve sınırları telefonun o anki durumundan üretilir
        if (Regex("^(neler|ne) yapabilirsin$|^neler yapabiliyorsun$|^yeteneklerin (neler|ne)$|^(neleri|ne) yapamazsın$|^sınırların (neler|ne)$|^kendini tanıt$|^sen kimsin$").matches(n)) {
            return p(CommandIntent.CAPABILITIES)
        }
        // 4d) Telefon durumu
        if (Regex("(telefon|cihaz|sistem)(un|ın)? durumu|pil (durumu|kaç|yüzde)|şarj(ım)? (ne durumda|kaç|yüzde)|batarya (durumu|kaç)|depolama alanı|telefon ısındı mı").containsMatchIn(n)) {
            return p(CommandIntent.DEVICE_STATUS)
        }
        // 4e) Konu takibi
        if (Regex("^(takip listem|takip ettiğin konular|neleri takip ediyorsun|takip listesini göster)$").matches(n)) {
            return p(CommandIntent.MONITOR, args = mapOf("action" to "list"))
        }
        Regex("^(.+?) (?:konusunu|konusundaki gelişmeleri|haberlerini|gelişmelerini) takip etmeyi bırak$|^(.+?) (?:konusunun |haberlerinin )?takibini (?:bırak|kaldır|durdur)$").find(s)?.let {
            val t = (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim()
            return p(CommandIntent.MONITOR, args = mapOf("action" to "remove", "topic" to t))
        }
        Regex("^(.+?) (?:konusunu|konusundaki gelişmeleri|haberlerini|gelişmelerini) takip et$").find(s)?.let {
            return p(CommandIntent.MONITOR, args = mapOf("action" to "add", "topic" to it.groupValues[1].trim()))
        }
        // 4f) Kamerayla bakma (görüntü analizi)
        Regex("^(?:kameraya|kamerayla|kamerandan) bak(?: ve)?(?: (.{3,}))?$").find(n)?.let {
            val q = it.groupValues[1].trim().ifEmpty { "Ne görüyorsun? Kısaca anlat." }
            return p(CommandIntent.CAMERA_LOOK, args = mapOf("question" to q))
        }
        if (n == "ne görüyorsun" || n == "bu ne" || n == "bak bakalım bu ne") return p(CommandIntent.CAMERA_LOOK, args = mapOf("question" to "Ne görüyorsun? Kısaca anlat."))
        // 4g) Günlük brifing (ajan: saat, takvim, hafıza, takip edilen konular, haberler)
        if (Regex("^(günaydın|günlük brifing|brifing ver|brifing|güne başlayalım|günün özeti|sabah brifingi)( jarvis)?$").matches(n)) {
            return p(CommandIntent.AGENT_TASK, args = mapOf("goal" to BRIEFING_GOAL), spoken = "")
        }
        // 5a) Ajan görevi: "ajan, ...", "görev: ..."
        Regex("^(?:ajan|görev|ajana görev ver)[:,]? (.{3,})$").find(n)?.let {
            return p(CommandIntent.AGENT_TASK, args = mapOf("goal" to it.groupValues[1].trim()), spoken = "")
        }
        // 5b') Belirli günün ajandası
        Regex("^(yarın|öbür gün|pazartesi|salı|çarşamba|perşembe|cuma|cumartesi|pazar) (?:ne(ler)? var|ne(ler)? yapmam gerek\\w*|programım ne|planım ne)").find(n)?.let {
            return p(CommandIntent.TODAY_AGENDA, args = mapOf("date" to it.groupValues[1]))
        }
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
        const val BRIEFING_GOAL = "Günlük brifing hazırla: bugünün tarihi ve saati; calendar_list ile bugünkü etkinlikler; " +
            "tasks_list ile bugünkü ve gecikmiş görevler; memory_recall ile hedefler ve projeler; web_research ile bugünün Türkiye ve dünyadan en önemli 3 haberi. " +
            "Sonucu finish ile 6-8 cümlelik, sesli okunacak, sıcak ve kısa bir sabah brifingi olarak ver. Bilgi alınamayan bölümü kısaca belirt."

        /** "yarın raporu gönder" -> başlık + tarih; "önemli" -> yüksek öncelik. */
        private fun taskArgs(raw: String): Map<String, String> = buildMap {
            var t = raw.trim()
            Regex("^(bugün|yarın|öbür gün) ").find(t)?.let { put("date", it.groupValues[1]); t = t.removePrefix(it.value) }
            if (Regex("\\b(önemli|acil)\\b").containsMatchIn(t)) { put("priority", "yüksek"); t = t.replace(Regex("\\b(önemli|acil)\\b[:,]?"), " ").replace(Regex("\\s+"), " ").trim() }
            put("title", t)
        }

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
