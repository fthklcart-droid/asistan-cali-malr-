package com.fk.jarvis.apps

import com.fk.jarvis.core.TextNormalizer

/** Cihazda kurulu ve başlatılabilir bir uygulama. */
data class AppInfo(val label: String, val packageName: String)

sealed class AppMatch {
    data class Found(val app: AppInfo) : AppMatch()
    data class Ambiguous(val candidates: List<AppInfo>) : AppMatch()
    data object NotFound : AppMatch()
}

/**
 * Söylenen uygulama adını cihazdaki gerçek uygulama listesiyle eşleştirir.
 * Paket adı TAHMİN EDİLMEZ: yalnızca cihazdan okunan listede olanlar döner.
 */
object AppMatcher {

    /** Türkçe söyleniş -> olası uygulama etiketleri (yine cihaz listesinde aranır). */
    private val aliases: Map<String, List<String>> = mapOf(
        "haritalar" to listOf("maps", "haritalar", "google maps"),
        "harita" to listOf("maps", "haritalar"),
        "googleharitalar" to listOf("maps", "haritalar"),
        "youtube" to listOf("youtube"),
        "whatsapp" to listOf("whatsapp"),
        "vatsap" to listOf("whatsapp"),
        "wasap" to listOf("whatsapp"),
        "capcut" to listOf("capcut"),
        "kepkat" to listOf("capcut"),
        "lightroom" to listOf("lightroom", "adobe lightroom", "lightroom mobile"),
        "photoshop" to listOf("photoshop", "photoshop express", "adobe photoshop"),
        "fotograflar" to listOf("photos", "fotoğraflar", "galeri", "gallery"),
        "galeri" to listOf("gallery", "galeri", "photos", "fotoğraflar"),
        "ayarlar" to listOf("settings", "ayarlar"),
        "takvim" to listOf("calendar", "takvim"),
        "rehber" to listOf("contacts", "kişiler", "rehber"),
        "kisiler" to listOf("contacts", "kişiler"),
        "mesajlar" to listOf("messages", "mesajlar"),
        "telefon" to listOf("phone", "telefon"),
        "hesapmakinesi" to listOf("calculator", "hesap makinesi"),
        "saat" to listOf("clock", "saat"),
        "instagram" to listOf("instagram"),
        "tiktok" to listOf("tiktok"),
        "spotify" to listOf("spotify"),
        "gmail" to listOf("gmail"),
        "chrome" to listOf("chrome"),
        "playstore" to listOf("play store", "google play store"),
    )

    fun match(spoken: String, installed: List<AppInfo>): AppMatch {
        val words = TextNormalizer.normalize(spoken).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return AppMatch.NotFound

        // Son sözcükte ek denemeleri: "whatsappı" -> "whatsapp"
        val variants = TextNormalizer.withoutAccusative(words.last()).map { last ->
            TextNormalizer.fold((words.dropLast(1) + last).joinToString(" "))
        }.distinct()

        val queries = linkedSetOf<String>()
        for (v in variants) {
            queries += v
            aliases[v]?.forEach { queries += TextNormalizer.fold(it) }
            // "google haritalar" -> "haritalar"
            if (v.startsWith("google") && v.length > 6) {
                val rest = v.removePrefix("google")
                queries += rest
                aliases[rest]?.forEach { queries += TextNormalizer.fold(it) }
            }
        }

        val scored = installed.map { app ->
            val label = TextNormalizer.fold(app.label)
            var best = 0
            for (q in queries) {
                if (q.length < 2) continue
                val sc = when {
                    label == q -> 100
                    label.startsWith(q) && q.length >= 3 -> 85
                    q.startsWith(label) && label.length >= 4 -> 75
                    label.contains(q) && q.length >= 4 -> 65
                    else -> 0
                }
                if (sc > best) best = sc
            }
            app to best
        }.filter { it.second > 0 }

        if (scored.isEmpty()) return AppMatch.NotFound
        val top = scored.maxOf { it.second }
        val best = scored.filter { it.second == top }.map { it.first }.distinctBy { it.packageName }
        return if (best.size == 1) AppMatch.Found(best[0]) else AppMatch.Ambiguous(best.sortedBy { it.label })
    }
}
