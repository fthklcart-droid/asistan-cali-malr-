package com.fkartmedia.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Hatırlatmalar: AlarmManager (kesin olmayan, pil dostu; birkaç dakika gecikebilir) + bildirim.
 * Kesin alarm izni (SCHEDULE_EXACT_ALARM) Android 14+ için varsayılan olarak kapalı olduğundan kullanılmaz.
 */
object Reminders {
    const val CHANNEL = "hatirlatmalar"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "FK JARVIS görev hatırlatmaları" })
        }
    }

    private fun pending(ctx: Context, todo: Todo): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (todo.id % Int.MAX_VALUE).toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", todo.id).putExtra("text", todo.text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(ctx: Context, todo: Todo) {
        val at = todo.remindAt ?: return
        if (at <= System.currentTimeMillis()) return
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, todo))
    }

    fun cancel(ctx: Context, todo: Todo) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, todo))
    }

    fun rescheduleAll(ctx: Context) {
        Store.loadTodos(ctx).filter { !it.done }.forEach { schedule(ctx, it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        val text = intent.getStringExtra("text") ?: return
        Reminders.ensureChannel(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ FK JARVIS hatırlatma")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(context).notify((id % Int.MAX_VALUE).toInt(), n)
        }
        // Hatırlatma bir kez gösterilir: görev listesinde zamanı temizle
        val todos = Store.loadTodos(context).map { if (it.id == id) it.copy(remindAt = null) else it }
        Store.saveTodos(context, todos)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(context)
    }
}
