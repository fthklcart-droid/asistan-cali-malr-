package com.fk.jarvis.apps

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * Cihaz takvimi (CalendarContract). Veriler cihazda kalır; yalnızca kullanıcı ajandayı sorduğunda ya da ajan
 * takvim aracını kullandığında okunur. Yazma (etkinlik ekleme) her zaman kullanıcı onayından sonra yapılır.
 * READ_CALENDAR / WRITE_CALENDAR izinleri yalnızca ilgili özellik ilk kullanıldığında istenir.
 */
class CalendarReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun today(): List<AgendaFormatter.Item> {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return range(start.timeInMillis, start.timeInMillis + DAY_MS).map { AgendaFormatter.Item(it.title, it.begin, it.allDay) }
    }

    /** [from] gününden başlayarak [days] gün içindeki etkinlikler (yinelenenler dahil, Instances tablosu). */
    fun events(from: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.coerceIn(1, 62).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return range(start, end)
    }

    fun range(startMs: Long, endMs: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMs)
            ContentUris.appendId(it, endMs)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 300) {
                out += CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(başlıksız)",
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5),
                    calendarName = c.getString(6),
                    color = if (c.isNull(7)) null else c.getInt(7),
                )
            }
        }
        return out
    }

    /** Etkinlik eklenebilecek takvimler (birincil önce). */
    fun writableCalendars(): List<CalendarAccount> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.IS_PRIMARY,
        )
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val out = mutableListOf<CalendarAccount>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
            while (c.moveToNext()) out += CalendarAccount(c.getLong(0), c.getString(1) ?: "Takvim", c.getString(2) ?: "", c.getInt(3) == 1)
        }
        return out.sortedByDescending { it.primary }
    }

    /**
     * Etkinliği doğrudan takvime yazar. Yazılabilir takvim yoksa null döner (çağıran taraf takvim uygulamasını açabilir).
     * @return eklenen etkinliğin kimliği
     */
    fun insert(e: NewEvent, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val cal = writableCalendars().firstOrNull() ?: return null
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, e.title)
            e.location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (e.time == null) {
                // Tüm gün etkinlikleri UTC gece yarısında saklanır.
                val start = e.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + DAY_MS)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val start = e.date.atTime(e.time).atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + e.durationMin * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        return ContentUris.parseId(uri)
    }

    /** Asistanın eklediği etkinliği siler (geri alma). */
    fun delete(eventId: Long): Boolean =
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0

    /** İzin ya da yazılabilir takvim yoksa: takvim uygulamasının "yeni etkinlik" ekranını önceden doldurarak açar. */
    fun insertIntent(e: NewEvent, zone: ZoneId = ZoneId.systemDefault()): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, e.title)
            e.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            if (e.time == null) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.date.atStartOfDay(zone).toInstant().toEpochMilli())
            } else {
                val start = e.date.atTime(e.time).atZone(zone).toInstant().toEpochMilli()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + e.durationMin * 60_000L)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Etkinliği takvim uygulamasında açar. */
    fun viewIntent(eventId: Long): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object { const val DAY_MS = 86_400_000L }
}
