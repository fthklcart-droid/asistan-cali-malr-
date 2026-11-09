package com.fk.jarvis.core

import java.util.Locale

/** Türkçe metin normalizasyonu (saf Kotlin, birim test edilebilir). */
object TextNormalizer {
    val TR: Locale = Locale.forLanguageTag("tr-TR")

    private val wakeWords = listOf("jarvis", "carvis", "cervis", "javis", "hey jarvis")

    /** Küçük harfe çevirir (Türkçe kurallarıyla), noktalama ve uyandırma sözcüğünü temizler. */
    fun normalize(input: String): String {
        var s = input.trim().lowercase(TR)
        s = s.replace('’', '\'').replace('`', '\'')
        // "07:45", "youtube.com" gibi kelime içi nokta/iki nokta korunur; cümle sonu noktalama silinir.
        s = s.replace(Regex("[!?,;\"“”()]+"), " ")
        s = s.replace(Regex("[.:]+(?=\\s|$)"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        for (w in wakeWords.sortedByDescending { it.length }) {
            // "JARVIS" Türkçe küçük harfe "jarvıs" olarak çevrilir; karşılaştırmada ı/i eşit sayılır.
            val head = s.take(w.length).replace('ı', 'i')
            if (head == w && (s.length == w.length || s[w.length] == ' ')) {
                s = s.drop(w.length).trim()
                break
            }
        }
        return s
    }

    /** "youtube'u" -> "youtube": kesme işaretinden sonraki eki atar. */
    fun stripApostropheSuffixes(s: String): String =
        s.replace(Regex("([\\p{L}\\p{N}]+)'[\\p{L}]+"), "$1")

    /** Eşleştirme için: Türkçe karakterleri ASCII'ye indirger, boşluk ve işaretleri siler. */
    fun fold(s: String): String {
        val lower = s.lowercase(TR)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            sb.append(
                when (c) {
                    'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'i' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                    'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("[^a-z0-9]"), "")
    }

    private val accusativeSuffixes = listOf("yu", "yü", "yı", "yi", "nu", "nü", "nı", "ni", "u", "ü", "ı", "i")

    /** Kesme işareti olmadan yazılmış belirtme hâli eklerini dener: "whatsappı" -> "whatsapp". */
    fun withoutAccusative(word: String): List<String> {
        val out = mutableListOf(word)
        for (suf in accusativeSuffixes) {
            if (word.length > suf.length + 2 && word.endsWith(suf)) out += word.dropLast(suf.length)
        }
        return out.distinct()
    }

    private val numberWords = mapOf(
        "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5, "altı" to 6, "yedi" to 7,
        "sekiz" to 8, "dokuz" to 9, "on" to 10, "yirmi" to 20, "otuz" to 30, "kırk" to 40,
        "elli" to 50, "altmış" to 60, "yarım" to 0,
    )

    /** "on beş" -> 15, "5" -> 5. Bulamazsa null. */
    fun parseNumber(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val parts = token.trim().split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var total = 0
        for (p in parts) total += numberWords[p] ?: return null
        return total
    }
}
