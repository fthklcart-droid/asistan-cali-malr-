package com.fk.jarvis.data

import com.fk.jarvis.core.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Kalıcı hafızadaki tek bir bilgi. */
data class MemoryFact(val category: String, val key: String, val value: String, val updated: Long)

/**
 * Kullanıcı hakkında kalıcı hafıza (cihazda, uygulamanın özel klasöründe JSON dosyası).
 * - Yalnızca kullanıcı açıkça istediğinde ya da kalıcı bir kişisel bilgi paylaştığında, kullanıcıya söylenerek kaydedilir.
 * - HAFIZA ekranında tümü görülebilir, tek tek ya da topluca silinebilir.
 * - Hafıza açıksa kısa bir özeti yapay zekâ isteğine bağlam olarak eklenir (ayarlardan kapatılabilir).
 * Parola, kart numarası, güvenlik kodu gibi gizli bilgiler hiçbir koşulda kaydedilmez.
 */
class MemoryStore(private val file: File) {
    private val _facts = MutableStateFlow(load())
    val facts: StateFlow<List<MemoryFact>> = _facts.asStateFlow()

    private fun load(): List<MemoryFact> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                MemoryFact(o.optString("category", "notlar"), o.optString("key"), o.optString("value"), o.optLong("updated"))
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    private fun save(list: List<MemoryFact>) {
        _facts.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("category", it.category).put("key", it.key).put("value", it.value).put("updated", it.updated)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
    }

    sealed class PutResult {
        data class Saved(val fact: MemoryFact, val previous: MemoryFact?) : PutResult()
        data class Refused(val reason: String) : PutResult()
    }

    /** Bilgiyi kaydeder (aynı anahtar varsa günceller). */
    fun put(category: String?, key: String?, value: String, now: Long = System.currentTimeMillis()): PutResult {
        val v = value.trim().replace(Regex("\\s+"), " ").take(MAX_VALUE)
        if (v.isEmpty()) return PutResult.Refused("Hatırlanacak bilgi boş.")
        if (isSecret(v) || isSecret(key.orEmpty())) return PutResult.Refused("Parola, kart numarası ya da güvenlik kodu gibi gizli bilgileri kaydetmiyorum.")
        val cat = normalizeCategory(category)
        val k = normalizeKey(key?.takeIf { it.isNotBlank() } ?: v.take(40))
        val list = _facts.value.toMutableList()
        val prev = list.firstOrNull { it.key == k }
        list.removeAll { it.key == k }
        val f = MemoryFact(cat, k, v, now)
        list += f
        if (list.size > MAX_FACTS) {
            list.sortByDescending { it.updated }
            while (list.size > MAX_FACTS) list.removeAt(list.size - 1)
        }
        save(list.sortedBy { it.updated })
        return PutResult.Saved(f, prev)
    }

    /** Anahtarı ya da değeri sorguyla eşleşen bilgileri siler; silinenleri döner. */
    fun forget(query: String): List<MemoryFact> {
        val hits = search(query, 50)
        if (hits.isEmpty()) return emptyList()
        save(_facts.value.filterNot { f -> hits.any { it.key == f.key } })
        return hits
    }

    fun remove(key: String) = save(_facts.value.filterNot { it.key == key })

    /** Geri alma için: önceki hâli geri yükler (null ise anahtarı siler). */
    fun restore(key: String, previous: MemoryFact?) {
        val list = _facts.value.filterNot { it.key == key }.toMutableList()
        if (previous != null) list += previous
        save(list.sortedBy { it.updated })
    }

    fun clear() = save(emptyList())

    fun all(): List<MemoryFact> = _facts.value

    /** Sorgu sözcüklerinin anahtar/değer/kategoride geçtiği bilgiler; boş sorgu her şeyi döner. */
    fun search(query: String, limit: Int = 10): List<MemoryFact> {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.length >= 2 }
        val list = _facts.value.sortedByDescending { it.updated }
        if (words.isEmpty()) return list.take(limit)
        return list.map { f ->
            val hay = TextNormalizer.normalize("${f.category} ${f.key.replace('_', ' ')} ${f.value}")
            f to words.count { w -> hay.contains(w) || (w.length > 4 && hay.contains(w.dropLast(2))) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Sesli okunacak özet. */
    fun describe(list: List<MemoryFact>): String =
        if (list.isEmpty()) "Bununla ilgili hafızamda bir bilgi yok."
        else "Hatırladıklarım: " + list.take(8).joinToString("; ") { it.value } + "."

    /** Yapay zekâ isteğine eklenen kısa hafıza bloğu (en yeni bilgiler önce, karakter sınırlı). */
    fun promptBlock(maxChars: Int = 900): String? {
        val list = _facts.value.sortedByDescending { it.updated }
        if (list.isEmpty()) return null
        val sb = StringBuilder()
        for (f in list) {
            val line = "${f.key.replace('_', ' ')}: ${f.value}"
            if (sb.length + line.length + 2 > maxChars) break
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append(line)
        }
        return sb.toString()
    }

    companion object {
        const val MAX_FACTS = 200
        const val MAX_VALUE = 300
        val CATEGORIES = listOf("kimlik", "tercihler", "projeler", "ilişkiler", "hedefler", "notlar")

        fun normalizeCategory(c: String?): String {
            val n = TextNormalizer.normalize(c.orEmpty())
            return when {
                n.isEmpty() -> "notlar"
                n.startsWith("ident") || n.startsWith("kimlik") -> "kimlik"
                n.startsWith("pref") || n.startsWith("tercih") -> "tercihler"
                n.startsWith("proj") -> "projeler"
                n.startsWith("rel") || n.startsWith("ilişki") || n.startsWith("aile") -> "ilişkiler"
                n.startsWith("wish") || n.startsWith("hedef") || n.startsWith("goal") || n.startsWith("plan") -> "hedefler"
                else -> if (n in CATEGORIES) n else "notlar"
            }
        }

        fun normalizeKey(k: String): String =
            TextNormalizer.normalize(k).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(48).ifEmpty { "not" }

        private val SECRET = Regex(
            "(şifre|parola|password|pin kod|pin'?im|cvv|cvc|güvenlik kodu|doğrulama kodu|iban|kart numara|kredi kart|\\b(?:\\d[ -]?){13,19}\\b)",
            RegexOption.IGNORE_CASE,
        )
        fun isSecret(s: String) = SECRET.containsMatchIn(s)
    }
}

/** Asistanın kendi yaptığı, geri alınabilir değişiklikler (en yeni üstte, en fazla 10). */
class UndoStack {
    data class Entry(val label: String, val undo: () -> Pair<Boolean, String>)
    private val items = ArrayDeque<Entry>()
    private val _labels = MutableStateFlow<List<String>>(emptyList())
    /** Arayüz için: geri alınabilecek işlemlerin adları (en yeni önce). */
    val labelsFlow: StateFlow<List<String>> = _labels.asStateFlow()

    @Synchronized fun push(label: String, undo: () -> Pair<Boolean, String>) {
        items.addFirst(Entry(label, undo))
        while (items.size > 10) items.removeLast()
        _labels.value = items.map { it.label }
    }

    @Synchronized fun undoLast(): Pair<Boolean, String> {
        val e = items.removeFirstOrNull() ?: return false to "Geri alabileceğim bir değişiklik yapmadım."
        _labels.value = items.map { it.label }
        return try { e.undo() } catch (ex: Exception) { false to "\"${e.label}\" geri alınamadı." }
    }

    @Synchronized fun labels(): List<String> = items.map { it.label }
}

/** Takip edilen konu (günde bir kez internette yeni gelişme aranır). */
data class Topic(val name: String, val added: Long, val lastCheck: Long = 0, val lastSummary: String = "")

/** Takip edilen konular (cihazda JSON). Finans/kripto alım-satım konuları kabul edilmez. */
class TopicStore(private val file: File) {
    private val _topics = MutableStateFlow(load())
    val topics: StateFlow<List<Topic>> = _topics.asStateFlow()

    private fun load(): List<Topic> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Topic(it.optString("name"), it.optLong("added"), it.optLong("lastCheck"), it.optString("lastSummary")) }
            }.filter { it.name.isNotBlank() }
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(list: List<Topic>) {
        _topics.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("added", it.added).put("lastCheck", it.lastCheck).put("lastSummary", it.lastSummary)) }
        file.parentFile?.mkdirs()
        file.writeText(a.toString())
    }

    /** null: eklendi; aksi hâlde neden eklenmediği. */
    fun add(name: String, now: Long = System.currentTimeMillis()): String? {
        val n = name.trim().replace(Regex("\\s+"), " ").take(80)
        if (n.length < 2) return "Takip edilecek konuyu anlayamadım."
        if (FINANCE.containsMatchIn(n)) return "Kripto, borsa ve alım-satım konularını takip etmiyorum."
        if (_topics.value.any { it.name.equals(n, ignoreCase = true) }) return "\"$n\" zaten takip ediliyor."
        if (_topics.value.size >= MAX) return "En fazla $MAX konu takip edilebilir; önce birini çıkar."
        save(_topics.value + Topic(n, now))
        return null
    }

    fun remove(name: String): Boolean {
        val n = TextNormalizer.normalize(name)
        val before = _topics.value
        val after = before.filterNot { TextNormalizer.normalize(it.name) == n || TextNormalizer.normalize(it.name).contains(n) }
        if (after.size == before.size) return false
        save(after); return true
    }

    fun update(name: String, summary: String, now: Long = System.currentTimeMillis()) =
        save(_topics.value.map { if (it.name == name) it.copy(lastCheck = now, lastSummary = summary.take(1500)) else it })

    fun all() = _topics.value

    companion object {
        const val MAX = 10
        private val FINANCE = Regex("kripto|bitcoin|btc|ethereum|borsa|hisse|forex|coin|al-?sat|trading|yatırım tavsiye", RegexOption.IGNORE_CASE)
        /** Araştırma yanıtında yeni gelişme yoksa model bu sözcükle başlar. */
        const val NOTHING = "YOK"

        fun query(topic: String) =
            "Son 24 saatte \"$topic\" konusunda önemli yeni bir gelişme var mı? Varsa 2-3 cümlede kaynak adlarıyla özetle. " +
                "Kayda değer yeni bir gelişme yoksa yalnızca \"$NOTHING\" yaz."

        fun isNews(answer: String, previous: String): Boolean {
            val a = answer.trim()
            if (a.isEmpty() || a.uppercase(TextNormalizer.TR).startsWith(NOTHING)) return false
            return TextNormalizer.normalize(a) != TextNormalizer.normalize(previous)
        }
    }
}
