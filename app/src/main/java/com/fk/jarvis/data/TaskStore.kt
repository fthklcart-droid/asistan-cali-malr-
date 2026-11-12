package com.fk.jarvis.data

import com.fk.jarvis.core.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Yapılacak iş. [due] YYYY-AA-GG, [time] SS:DD (ikisi de isteğe bağlı). [priority] 0 düşük, 1 normal, 2 yüksek. */
data class Task(
    val id: Long,
    val title: String,
    val due: String? = null,
    val time: String? = null,
    val priority: Int = 1,
    val project: String = "",
    val done: Boolean = false,
    val created: Long = 0,
    val doneAt: Long = 0,
)

/** Serbest not (toplantı notu, fikir, müşteri bilgisi). */
data class Note(val id: Long, val text: String, val created: Long)

/** Tek bir JSON dosyasında liste saklayan küçük yardımcı. */
private fun readArray(file: File): JSONArray = try { if (file.exists()) JSONArray(file.readText()) else JSONArray() } catch (e: Exception) { JSONArray() }
private fun writeArray(file: File, a: JSONArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(a.toString())
    if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
}

/**
 * Görevler (yapılacaklar) — cihazda saklanır. PLAN bölmesi, sesli komutlar ve ajan aynı depoyu kullanır.
 */
class TaskStore(private val file: File) {
    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private fun load(): List<Task> {
        val a = readArray(file)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Task(
                o.optLong("id"), o.optString("title"), o.optString("due").ifBlank { null }, o.optString("time").ifBlank { null },
                o.optInt("priority", 1), o.optString("project"), o.optBoolean("done"), o.optLong("created"), o.optLong("doneAt"),
            )
        }.filter { it.title.isNotBlank() }
    }

    @Synchronized private fun save(list: List<Task>) {
        _tasks.value = list
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due ?: "").put("time", it.time ?: "")
                .put("priority", it.priority).put("project", it.project).put("done", it.done).put("created", it.created).put("doneAt", it.doneAt))
        }
        writeArray(file, a)
    }

    fun all(): List<Task> = _tasks.value
    fun open(): List<Task> = sorted(_tasks.value.filter { !it.done })

    /** Sıralama: önce tarihi olanlar (erken önce), sonra öncelik, sonra eklenme sırası. */
    fun sorted(list: List<Task>): List<Task> =
        list.sortedWith(compareBy<Task>({ it.due ?: "9999" }, { it.time ?: "99:99" }, { -it.priority }, { it.created }))

    @Synchronized fun add(title: String, due: String? = null, time: String? = null, priority: Int = 1, project: String = "", now: Long = System.currentTimeMillis()): Task? {
        val t = title.trim().replace(Regex("\\s+"), " ").take(200)
        if (t.isEmpty()) return null
        val list = _tasks.value.toMutableList()
        // Tamamlanmış eski görevler birikmesin: en fazla 300 kayıt.
        while (list.size >= MAX) (list.firstOrNull { it.done } ?: list.first()).let { list.remove(it) }
        val task = Task((list.maxOfOrNull { it.id } ?: 0) + 1, t, due, time, priority.coerceIn(0, 2), project.trim().take(40), false, now)
        save(list + task)
        return task
    }

    /** Başlığı sorguyla eşleşen açık görevler (tam eşleşme önce). */
    fun find(query: String): List<Task> {
        val q = TextNormalizer.normalize(query)
        if (q.isBlank()) return emptyList()
        val open = open()
        open.filter { TextNormalizer.normalize(it.title) == q }.takeIf { it.isNotEmpty() }?.let { return it }
        val words = q.split(' ').filter { it.length >= 2 }
        return open.map { t ->
            val hay = TextNormalizer.normalize(t.title)
            t to words.count { w -> hay.contains(w) || (w.length > 4 && hay.contains(w.dropLast(2))) }
        }.filter { it.second > 0 && it.second * 2 >= words.size }.sortedByDescending { it.second }.map { it.first }
    }

    @Synchronized fun setDone(id: Long, done: Boolean, now: Long = System.currentTimeMillis()): Task? {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return null
        val n = t.copy(done = done, doneAt = if (done) now else 0)
        save(_tasks.value.map { if (it.id == id) n else it })
        return n
    }

    @Synchronized fun remove(id: Long): Task? {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return null
        save(_tasks.value.filterNot { it.id == id })
        return t
    }

    /** Geri alma için: silinen görevi geri koyar. */
    @Synchronized fun restore(t: Task) { if (_tasks.value.none { it.id == t.id }) save(_tasks.value + t) }

    @Synchronized fun clearDone() = save(_tasks.value.filterNot { it.done })

    /** [day] günü için: o güne tarihli + gecikmiş açık görevler. */
    fun forDay(day: LocalDate): List<Task> {
        val d = day.toString()
        return open().filter { it.due != null && it.due <= d }
    }

    companion object {
        const val MAX = 300
        val PRIORITY = listOf("düşük", "normal", "yüksek")

        fun parsePriority(raw: String?): Int {
            val n = TextNormalizer.normalize(raw.orEmpty())
            return when {
                n.isEmpty() -> 1
                n.startsWith("yük") || n.startsWith("acil") || n.startsWith("önemli") || n == "high" || n == "2" -> 2
                n.startsWith("düş") || n == "low" || n == "0" -> 0
                else -> 1
            }
        }

        /** Sesli okunacak / ajana verilecek özet. */
        fun describe(list: List<Task>, today: LocalDate, title: String = "Açık görevlerin"): String {
            if (list.isEmpty()) return "Açık görevin yok."
            val t = today.toString()
            val parts = list.take(10).map { x ->
                val whenText = when {
                    x.due == null -> ""
                    x.due < t -> " (gecikmiş, ${x.due})"
                    x.due == t -> " (bugün${x.time?.let { " $it" } ?: ""})"
                    x.due == today.plusDays(1).toString() -> " (yarın${x.time?.let { " $it" } ?: ""})"
                    else -> " (${x.due}${x.time?.let { " $it" } ?: ""})"
                }
                (if (x.priority == 2) "önemli: " else "") + x.title + whenText + (if (x.project.isNotBlank()) " [${x.project}]" else "")
            }
            val more = if (list.size > 10) " ve ${list.size - 10} görev daha" else ""
            return "$title (${list.size}): ${parts.joinToString("; ")}$more."
        }
    }
}

/** Notlar — cihazda saklanır. */
class NoteStore(private val file: File) {
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private fun load(): List<Note> {
        val a = readArray(file)
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Note(it.optLong("id"), it.optString("text"), it.optLong("created")) } }.filter { it.text.isNotBlank() }
    }

    @Synchronized private fun save(list: List<Note>) {
        _notes.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created)) }
        writeArray(file, a)
    }

    fun all(): List<Note> = _notes.value.sortedByDescending { it.created }

    /** Gizli bilgi (parola, kart, kod) içeren not kaydedilmez: null döner. */
    @Synchronized fun add(text: String, now: Long = System.currentTimeMillis()): Note? {
        val t = text.trim().take(4000)
        if (t.isEmpty() || MemoryStore.isSecret(t)) return null
        val list = _notes.value.toMutableList()
        while (list.size >= MAX) list.removeAt(0)
        val n = Note((list.maxOfOrNull { it.id } ?: 0) + 1, t, now)
        save(list + n)
        return n
    }

    @Synchronized fun remove(id: Long): Note? {
        val n = _notes.value.firstOrNull { it.id == id } ?: return null
        save(_notes.value.filterNot { it.id == id })
        return n
    }

    @Synchronized fun restore(n: Note) { if (_notes.value.none { it.id == n.id }) save(_notes.value + n) }

    fun search(query: String, limit: Int = 8): List<Note> {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return all().take(limit)
        return all().map { n -> n to words.count { TextNormalizer.normalize(n.text).contains(it) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    companion object { const val MAX = 300 }
}
