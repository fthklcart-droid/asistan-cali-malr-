package com.fk.jarvis.apps

import com.fk.jarvis.core.TextNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Cihaz takviminden okunan bir etkinlik (Android'e bağımlı değil; testlerde de kullanılır). */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String? = null,
    val calendarName: String? = null,
    val color: Int? = null,
)

/** Yazılabilir bir takvim hesabı. */
data class CalendarAccount(val id: Long, val name: String, val account: String, val primary: Boolean)

/** Takvime eklenecek etkinlik isteği (doğrulanmış). */
data class NewEvent(
    val title: String,
    val date: LocalDate,
    /** null ise tüm gün. */
    val time: LocalTime?,
    val durationMin: Int = 60,
    val location: String? = null,
)

/**
 * Türkçe tarih/saat ifadelerini çözümler ve takvim bilgisini sesli okunacak metne çevirir.
 * Desteklenen tarih biçimleri: 2026-11-10, 10.11.2026, 10/11, "bugün", "yarın", "öbür gün",
 * "10 kasım", "pazartesi" (bugünden sonraki ilk), "haftaya pazartesi".
 */
object CalendarText {
    private val TR = TextNormalizer.TR
    val MONTHS = listOf("ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık")
    val DAYS = listOf("pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar")

    fun parseDate(raw: String?, today: LocalDate): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(TR)
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("^(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?$").find(s)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return runCatching { rollForward(LocalDate.of(y ?: today.year, mo, d), today, y == null) }.getOrNull()
        }
        when {
            s == "bugün" || s == "bu gün" -> return today
            s == "yarın" -> return today.plusDays(1)
            s == "öbür gün" || s == "ertesi gün" || s == "yarından sonra" -> return today.plusDays(2)
        }
        Regex("^(\\d{1,2}) (${MONTHS.joinToString("|")})(?: (\\d{4}))?").find(s)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[2]) + 1
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            return runCatching { rollForward(LocalDate.of(y ?: today.year, mo, m.groupValues[1].toInt()), today, y == null) }.getOrNull()
        }
        val nextWeek = s.startsWith("haftaya ") || s.startsWith("gelecek ") || s.startsWith("önümüzdeki ")
        val dayWord = s.substringAfter(' ', s).takeIf { nextWeek } ?: s
        val di = DAYS.indexOf(dayWord.removeSuffix("ya").removeSuffix("ye").trim())
        if (di >= 0) {
            val todayIdx = today.dayOfWeek.value - 1
            var delta = (di - todayIdx + 7) % 7
            if (delta == 0) delta = 7
            if (nextWeek && delta < 7) delta += 7
            return today.plusDays(delta.toLong())
        }
        return null
    }

    /** Yıl belirtilmemiş ve tarih geçmişte kalmışsa gelecek yıla taşınır. */
    private fun rollForward(d: LocalDate, today: LocalDate, yearImplicit: Boolean): LocalDate =
        if (yearImplicit && d.isBefore(today)) d.plusYears(1) else d

    /** "14:30", "14.30", "14", "saat 9" -> (saat, dakika). Geçersizse null. */
    fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(TR).removePrefix("saat ").trim()
        val m = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }

    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMMM EEEE", TR)
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", TR)

    fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Bugün"
        today.plusDays(1) -> "Yarın"
        else -> d.format(DATE_FMT)
    }

    fun describeNew(e: NewEvent, today: LocalDate): String {
        val day = dayLabel(e.date, today).let { if (it == "Bugün" || it == "Yarın") it.lowercase(TR) else e.date.format(DATE_FMT) }
        val time = e.time?.let { " saat ${it.format(TIME_FMT)}" } ?: " tüm gün"
        val loc = e.location?.takeIf { it.isNotBlank() }?.let { ", yer: $it" } ?: ""
        return "\"${e.title}\" — $day$time${if (e.time != null) ", ${e.durationMin} dakika" else ""}$loc"
    }

    /** Etkinlik listesini kısa, sesli okunabilir metne çevirir (ajan gözlemi ve sesli yanıt için). */
    fun describe(events: List<CalendarEvent>, from: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val range = if (days <= 1) dayLabel(from, today).lowercase(TR) else "${dayLabel(from, today).lowercase(TR)} başlayarak $days gün içinde"
        if (events.isEmpty()) return "Takviminde $range kayıtlı etkinlik yok."
        val byDay = events.sortedBy { it.begin }.groupBy { Instant.ofEpochMilli(it.begin).atZone(if (it.allDay) ZoneId.of("UTC") else zone).toLocalDate() }
        val parts = byDay.entries.take(10).map { (d, list) ->
            dayLabel(d, today) + ": " + list.take(8).joinToString(", ") { e ->
                val t = if (e.allDay) "gün boyu" else Instant.ofEpochMilli(e.begin).atZone(zone).toLocalTime().format(TIME_FMT)
                "$t ${e.title}" + (e.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            }
        }
        return "Takviminde $range ${events.size} etkinlik var. " + parts.joinToString(". ") + "."
    }
}
