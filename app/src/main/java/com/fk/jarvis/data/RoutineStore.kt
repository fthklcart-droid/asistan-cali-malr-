package com.fk.jarvis.data

import com.fk.jarvis.core.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Rutin: asistanın belirli zamanda kendiliğinden yaptığı iş. Hepsi kullanıcı tarafından kurulur, listelenir ve silinir.
 * - [Kind.ONCE]: [date] + [time] (hatırlatıcı) · [Kind.DAILY]: her gün [time] · [Kind.WEEKLY]: [days] (1=Pzt … 7=Paz) günlerinde [time]
 * - [Action.REMIND]: bildirim + (uygulama açıksa) sesli hatırlatma · [Action.SAY]: metni seslendir · [Action.TASK]: metni ajana görev olarak ver
 */
data class Routine(
    val id: Long,
    val title: String,
    val kind: Kind,
    val time: String,
    val date: String? = null,
    val days: Set<Int> = emptySet(),
    val action: Action = Action.REMIND,
    val text: String = "",
    val enabled: Boolean = true,
    /** Son tetiklenen an (YYYY-AA-GGTSS:DD) — aynı dakika iki kez çalışmasın diye. */
    val lastFired: String = "",
    val created: Long = 0,
) {
    enum class Kind { ONCE, DAILY, WEEKLY }
    enum class Action { REMIND, SAY, TASK }

    /** [after] anından SONRAKİ ilk çalışma zamanı; yoksa (geçmiş tek seferlik, kapalı) null. */
    fun nextFire(after: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        val t = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
        return when (kind) {
            Kind.ONCE -> {
                val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
                d.atTime(t).takeIf { it.isAfter(after) }
            }
            Kind.DAILY -> after.toLocalDate().atTime(t).let { if (it.isAfter(after)) it else it.plusDays(1) }
            Kind.WEEKLY -> {
                if (days.isEmpty()) return null
                (0..7).asSequence().map { after.toLocalDate().plusDays(it.toLong()).atTime(t) }
                    .firstOrNull { it.isAfter(after) && it.dayOfWeek.value in days }
            }
        }
    }

    fun scheduleText(): String = when (kind) {
        Kind.ONCE -> "${date.orEmpty()} $time"
        Kind.DAILY -> "Her gün $time"
        Kind.WEEKLY -> days.sorted().joinToString(", ") { DAY_SHORT[it - 1] } + " $time"
    }

    companion object { val DAY_SHORT = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz") }
}

class RoutineStore(private val file: File) {
    private val _routines = MutableStateFlow(load())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private fun load(): List<Routine> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Routine(
                    o.optLong("id"), o.optString("title"),
                    runCatching { Routine.Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null,
                    o.optString("time"), o.optString("date").ifBlank { null },
                    o.optString("days").split(',').mapNotNull { it.toIntOrNull() }.toSet(),
                    runCatching { Routine.Action.valueOf(o.optString("action")) }.getOrDefault(Routine.Action.REMIND),
                    o.optString("text"), o.optBoolean("enabled", true), o.optString("lastFired"), o.optLong("created"),
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(list: List<Routine>) {
        _routines.value = list
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("kind", it.kind.name).put("time", it.time).put("date", it.date ?: "")
                .put("days", it.days.sorted().joinToString(",")).put("action", it.action.name).put("text", it.text)
                .put("enabled", it.enabled).put("lastFired", it.lastFired).put("created", it.created))
        }
        file.parentFile?.mkdirs()
        file.writeText(a.toString())
    }

    fun all(): List<Routine> = _routines.value

    /** Doğrulanmış rutini ekler; geçersizse (saat bozuk, geçmiş tarih, gün yok, sınır dolu) nedenini döner. */
    @Synchronized fun add(r: Routine, now: LocalDateTime = LocalDateTime.now()): Pair<Routine?, String?> {
        if (r.title.isBlank()) return null to "Rutinin adını anlayamadım."
        if (runCatching { LocalTime.parse(r.time) }.isFailure) return null to "Saati anlayamadım."
        if (_routines.value.size >= MAX) return null to "En fazla $MAX rutin olabilir; önce birini sil."
        val n = r.copy(id = (_routines.value.maxOfOrNull { it.id } ?: 0) + 1, title = r.title.trim().take(120), text = r.text.trim().take(1500), enabled = true, lastFired = "")
        if (n.nextFire(now) == null) return null to (if (r.kind == Routine.Kind.ONCE) "Bu zaman geçmişte kalmış." else "Hangi günlerde çalışacağını anlayamadım.")
        save(_routines.value + n)
        return n to null
    }

    @Synchronized fun remove(id: Long): Routine? {
        val r = _routines.value.firstOrNull { it.id == id } ?: return null
        save(_routines.value.filterNot { it.id == id }); return r
    }

    @Synchronized fun restore(r: Routine) { if (_routines.value.none { it.id == r.id }) save(_routines.value + r) }

    @Synchronized fun setEnabled(id: Long, on: Boolean) = save(_routines.value.map { if (it.id == id) it.copy(enabled = on) else it })

    fun find(query: String): List<Routine> {
        val q = TextNormalizer.normalize(query)
        return _routines.value.filter { TextNormalizer.normalize(it.title + " " + it.text).contains(q) }
    }

    /** En yakın çalışma zamanı (alarm kurmak için). */
    fun nextFire(after: LocalDateTime): LocalDateTime? = _routines.value.mapNotNull { it.nextFire(after) }.minOrNull()

    /**
     * [now] anında vadesi gelen rutinleri döner ve işaretler: son [graceMin] dakika içinde çalışması gereken,
     * henüz bu zaman için tetiklenmemiş olanlar. Tek seferlikler tetiklenince kapatılır.
     */
    @Synchronized fun takeDue(now: LocalDateTime, graceMin: Long = 30): List<Routine> {
        val due = mutableListOf<Routine>()
        val updated = _routines.value.map { r ->
            val at = r.nextFire(now.minusMinutes(graceMin))
            if (at != null && !at.isAfter(now) && r.lastFired != at.toString()) {
                due += r
                r.copy(lastFired = at.toString(), enabled = r.kind != Routine.Kind.ONCE)
            } else r
        }
        if (due.isNotEmpty()) save(updated)
        return due
    }

    companion object {
        const val MAX = 40

        /** "23:00"–"07:00" gibi (gece yarısını aşabilen) sessiz saat aralığında mı. */
        fun inQuietHours(now: LocalTime, start: String, end: String): Boolean {
            val s = runCatching { LocalTime.parse(start) }.getOrNull() ?: return false
            val e = runCatching { LocalTime.parse(end) }.getOrNull() ?: return false
            if (s == e) return false
            return if (s < e) now >= s && now < e else now >= s || now < e
        }

        /** "her gün", "hafta içi", "pazartesi çarşamba", "weekly:1,3" -> tür ve günler. */
        fun parseRepeat(raw: String?): Pair<Routine.Kind, Set<Int>> {
            val n = TextNormalizer.normalize(raw.orEmpty())
            if (n.isEmpty() || n == "once" || n == "bir kez" || n == "tek sefer") return Routine.Kind.ONCE to emptySet()
            if (n.contains("her gün") || n == "daily" || n == "günlük") return Routine.Kind.DAILY to emptySet()
            if (n.contains("hafta içi") || n == "weekdays") return Routine.Kind.WEEKLY to setOf(1, 2, 3, 4, 5)
            if (n.contains("hafta sonu") || n == "weekend") return Routine.Kind.WEEKLY to setOf(6, 7)
            val names = listOf("pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar")
            val en = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            val days = mutableSetOf<Int>()
            // "cumartesi" içinde "cuma", "pazartesi" içinde "pazar" geçer: uzun adlar önce ayıklanır.
            var rest = " $n "
            for (i in listOf(0, 5, 1, 2, 3, 4, 6)) {
                if (rest.contains(names[i]) || rest.contains(en[i])) { days += i + 1; rest = rest.replace(names[i], " ").replace(en[i], " ") }
            }
            Regex("\\b([1-7])\\b").findAll(rest).forEach { days += it.groupValues[1].toInt() }
            return if (days.isEmpty()) Routine.Kind.ONCE to emptySet() else Routine.Kind.WEEKLY to days
        }
    }
}
