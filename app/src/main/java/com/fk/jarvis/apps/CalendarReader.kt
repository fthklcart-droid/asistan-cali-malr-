package com.fk.jarvis.apps

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Bugünün takvim etkinliklerini cihazdan OKUR (yazmaz). Veriler cihazda kalır, yapay zekâya gönderilmez.
 * READ_CALENDAR izni yalnızca kullanıcı ajandasını sorduğunda istenir.
 */
class CalendarReader(private val context: Context) {


    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun today(): List<AgendaFormatter.Item> {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start.timeInMillis)
            ContentUris.appendId(it, end.timeInMillis)
        }.build()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
        val out = mutableListOf<AgendaFormatter.Item>()
        context.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                out += AgendaFormatter.Item(c.getString(0) ?: "(başlıksız)", c.getLong(1), c.getInt(2) == 1)
            }
        }
        return out
    }

}
