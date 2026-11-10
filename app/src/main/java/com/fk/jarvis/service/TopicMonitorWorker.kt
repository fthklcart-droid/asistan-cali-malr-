package com.fk.jarvis.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fk.jarvis.JarvisApp
import com.fk.jarvis.MainActivity
import com.fk.jarvis.R
import com.fk.jarvis.data.TopicStore
import com.fk.jarvis.llm.LlmException
import java.util.concurrent.TimeUnit

/**
 * Takip edilen konular için günde bir kez internette yeni gelişme arar; yeni bir şey varsa bildirim gösterir.
 * Yalnızca kullanıcı en az bir konu eklediğinde planlanır; konu kalmayınca iptal edilir. Ağ bağlantısı gerektirir.
 */
class TopicMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as JarvisApp
        val provider = app.ai.current()
        if (!provider.isConfigured || !provider.supportsResearch) return Result.success()
        var notified = 0
        for (t in app.topics.all()) {
            val answer = try {
                provider.research(TopicStore.query(t.name), mapOf("dil" to "tr-TR"))
            } catch (e: LlmException) {
                if (e.kind == LlmException.Kind.NETWORK || e.kind == LlmException.Kind.TIMEOUT) return Result.retry()
                continue
            }
            if (TopicStore.isNews(answer, t.lastSummary)) {
                app.topics.update(t.name, answer)
                if (notify(applicationContext, t.name, answer)) notified++
            } else {
                app.topics.update(t.name, t.lastSummary)
            }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "jarvis-topic-monitor"
        private const val NOW = "jarvis-topic-monitor-now"
        private const val CHANNEL = "topics"

        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

        /** Konu varsa günlük işi planlar (varsa korur), yoksa iptal eder. */
        fun sync(context: Context, hasTopics: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!hasTopics) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<TopicMonitorWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** "ŞİMDİ KONTROL ET" düğmesi. */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<TopicMonitorWorker>().setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun notify(context: Context, topic: String, text: String): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Takip edilen konular", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Takip ettiğin konulardaki yeni gelişmeler (günde en fazla bir kez)"
                })
            }
            val open = PendingIntent.getActivity(
                context, topic.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("Yeni gelişme: $topic")
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return try { NotificationManagerCompat.from(context).notify(topic.hashCode(), n); true } catch (e: SecurityException) { false }
        }
    }
}
