package com.fk.jarvis.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fk.jarvis.JarvisApp
import com.fk.jarvis.MainActivity
import com.fk.jarvis.R
import com.fk.jarvis.data.Routine
import com.fk.jarvis.data.RoutineStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Rutinleri ve hatırlatıcıları zamanında çalıştırır. Her an yalnızca EN YAKIN rutin için tek bir alarm kuruludur;
 * alarm çalınca vadesi gelenler işlenir ve bir sonraki alarm kurulur.
 *
 * Zamanlama: "Alarmlar ve hatırlatıcılar" izni açıksa alarm tam dakikasında çalar. İzin kapalıysa (Android 14+
 * sürümlerde varsayılan) alarm yine kurulur ama sistem pil tasarrufu için geciktirebilir (uzun süre sonrasına kurulan
 * hatırlatıcıda bir saate kadar). Uygulama ekranda değilken yalnızca bildirim gösterilir:
 * seslendirme ve ajan görevi, uygulama açıkken ya da bildirime dokununca çalışır (habersiz arka plan işlemi yapılmaz).
 */
object RoutineScheduler {
    private const val CHANNEL = "routines"
    const val EXTRA_TASK = "routine_task"
    const val EXTRA_SAY = "routine_say"

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7001, Intent(context, RoutineReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tam zamanlı alarm kurulabiliyor mu (Android 12+ sürümlerde kullanıcı izni gerekir). */
    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching { context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)
    }

    /** "Alarmlar ve hatırlatıcılar" izin ekranını açan intent (Android 12+). */
    fun exactAlarmSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 31) Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName)) else null

    /** En yakın rutine alarm kurar; rutin yoksa alarmı kaldırır. */
    fun schedule(context: Context) {
        val app = context.applicationContext as JarvisApp
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = app.routines.nextFire(LocalDateTime.now())
        val pi = pending(context)
        if (next == null) { am.cancel(pi); return }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Alarm çaldı (ya da uygulama açıldı): vadesi gelen rutinleri çalıştırır. */
    fun runDue(context: Context) {
        val app = context.applicationContext as JarvisApp
        val now = LocalDateTime.now()
        val s = app.settings.current
        val quiet = s.quietHours && RoutineStore.inQuietHours(now.toLocalTime(), s.quietStart, s.quietEnd)
        for (r in app.routines.takeDue(now)) {
            // Sessiz saatlerde tekrarlayan rutinler konuşmaz; kullanıcının belirli bir an için kurduğu tek seferlik hatırlatıcı çalışır.
            val silent = quiet && r.kind != Routine.Kind.ONCE
            val visible = app.uiVisible
            when (r.action) {
                Routine.Action.REMIND, Routine.Action.SAY -> {
                    val text = r.text.ifBlank { r.title }
                    if (visible && !silent) app.core.speak(if (r.action == Routine.Action.REMIND) "Hatırlatma: $text" else text)
                    notify(context, r, if (r.action == Routine.Action.REMIND) "Hatırlatma" else "Rutin", text, sayOnOpen = if (visible || silent) null else text, task = null, silent = silent)
                }
                Routine.Action.TASK -> {
                    val started = visible && !silent && app.agent.start(r.text.ifBlank { r.title })
                    notify(
                        context, r, if (started) "Ajan rutini başladı" else "Ajan rutini hazır — başlatmak için dokun",
                        r.text.ifBlank { r.title }, sayOnOpen = null, task = if (started) null else r.text.ifBlank { r.title }, silent = silent,
                    )
                }
            }
        }
        schedule(context)
    }

    private fun notify(context: Context, r: Routine, head: String, text: String, sayOnOpen: String?, task: String?, silent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rutinler ve hatırlatıcılar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Senin kurduğun hatırlatıcılar ve rutinler"
            })
        }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        task?.let { open.putExtra(EXTRA_TASK, it) }
        sayOnOpen?.let { open.putExtra(EXTRA_SAY, it) }
        val pi = PendingIntent.getActivity(context, 8000 + r.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("$head: ${r.title}")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(800)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { NotificationManagerCompat.from(context).notify(8000 + r.id.toInt(), n) } catch (_: SecurityException) {}
    }

    /** Ajan görevi arka planda bittiğinde. */
    fun notifyReport(context: Context, goal: String, text: String, ok: Boolean) {
        notify(context, Routine(900, goal.take(60), Routine.Kind.ONCE, "00:00"), if (ok) "Ajan raporu hazır" else "Ajan görevi tamamlanamadı", text, null, null, false)
    }
}

/** Rutin alarmı. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = RoutineScheduler.runDue(context)
}

/** Telefon yeniden başlatıldığında ya da uygulama güncellendiğinde alarmı yeniden kurar (alarmlar yeniden başlatmada silinir). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) RoutineScheduler.runDue(context)
    }
}
