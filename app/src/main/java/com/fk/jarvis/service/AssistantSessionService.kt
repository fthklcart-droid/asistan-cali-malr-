package com.fk.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fk.jarvis.JarvisApp
import com.fk.jarvis.MainActivity
import com.fk.jarvis.R
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.voice.JarvisSpeechRecognizer
import com.fk.jarvis.voice.WakeWordLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Sesli asistan oturumu için ön plan hizmeti (Android 14+ türü: microphone).
 *
 * Ne zaman çalışır:
 *  - Kullanıcı ayarlardan "JARVIS uyandırma sözcüğü"nü açtıysa (kalıcı, bildirimli), veya
 *  - Sesli sohbet oturumu açıkken (ör. "YouTube'u aç" dedikten sonra uygulama arka plana geçse bile
 *    sohbetin sürebilmesi için; oturum bitince kendini kapatır).
 *
 * Android kuralları: mikrofon türündeki ön plan hizmeti yalnızca uygulama öndeyken başlatılabilir;
 * bu yüzden hizmet her zaman uygulama ekranından başlatılır. Bildirim her zaman görünür; "Durdur" ile kapanır.
 * Pil: ekran kapalıyken uyandırma sözcüğü varsayılan olarak dinlenmez.
 */
class AssistantSessionService : Service() {

    private val app get() = application as JarvisApp
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var wake: WakeWordLoop? = null
    private var screenOn = true
    private var lastNotif = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        wake = WakeWordLoop(
            stt = JarvisSpeechRecognizer(this, preferOnDevice = true),
            onWake = { cmd -> app.core.onWakeWord(cmd) },
            onUnavailable = { updateNotification("Uyandırma sözcüğü kullanılamıyor (mikrofon ya da tanıma hizmeti yok)") },
        )
        // Çekirdek mikrofonu kullanmadan önce uyandırma döngüsünü durdur (iki tanıyıcı aynı anda çalışmasın).
        app.core.onMicRequested = { wake?.stop() }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(app.core.ui, app.core.conversationActive, app.settings.settings) { ui, conv, st -> Triple(ui.state, conv, st.wakeWord) }
                .collect { evaluate() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = goForeground()
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_LISTEN -> { wake?.stop(); app.core.startVoiceSession() }
            ACTION_STOP -> {
                app.core.endSession()
                app.settings.update { it.copy(wakeWord = false) }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        evaluate()
        // Sistem hizmeti öldürürse yeniden başlatılmaz: mikrofon izni ön planda başlatmayı gerektirir.
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(statusText()), type)
        true
    } catch (e: Exception) {
        // Android 14+: uygulama arka plandayken mikrofon türünde ön plan hizmeti başlatılamaz.
        false
    }

    /** Duruma göre uyandırma döngüsünü çalıştırır/duraklatır ve gerekmiyorsa hizmeti kapatır. */
    private fun evaluate() {
        val st = app.settings.current
        val ui = app.core.ui.value
        val conv = app.core.conversationActive.value
        val idle = ui.state == AssistantState.IDLE || ui.state == AssistantState.ERROR
        val wantWake = st.wakeWord && idle && !conv && (screenOn || st.wakeWhileScreenOff)
        if (wantWake) {
            if (wake?.active != true) main.postDelayed({ if (shouldStillWake()) wake?.start() }, 700)
        } else {
            main.removeCallbacksAndMessages(null)
            wake?.stop()
        }
        if (!st.wakeWord && !conv && idle) {
            // Oturum bitti ve uyandırma sözcüğü kapalı: hizmete gerek yok.
            main.postDelayed({ if (!app.settings.current.wakeWord && !app.core.conversationActive.value) stopSelf() }, 5_000)
        }
        updateNotification(statusText())
    }

    private fun shouldStillWake(): Boolean {
        val st = app.settings.current
        val ui = app.core.ui.value
        return st.wakeWord && !app.core.conversationActive.value &&
            (ui.state == AssistantState.IDLE || ui.state == AssistantState.ERROR) && (screenOn || st.wakeWhileScreenOff)
    }

    private fun statusText(): String {
        val st = app.settings.current
        val ui = app.core.ui.value
        return when {
            ui.state == AssistantState.LISTENING -> "Dinliyorum…"
            ui.state == AssistantState.SPEAKING -> "Konuşuyor…"
            ui.state == AssistantState.PROCESSING || ui.state == AssistantState.EXECUTING -> "İşleniyor…"
            st.wakeWord && !screenOn && !st.wakeWhileScreenOff -> "Ekran kapalı — uyandırma sözcüğü duraklatıldı"
            st.wakeWord -> "\"${st.assistantName}\" demen yeterli"
            else -> "Sesli oturum açık"
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotif) return
        lastNotif = text
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        } catch (_: SecurityException) {}
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val listen = PendingIntent.getService(this, 1, Intent(this, AssistantSessionService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistantSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(getString(R.string.app_name) + " · mikrofon kullanımda olabilir")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Dinle", listen)
            .addAction(0, "Durdur", stop)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        wake?.stop()
        if (app.core.onMicRequested != null) app.core.onMicRequested = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        scope?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_session"
        const val NOTIF_ID = 42
        const val ACTION_LISTEN = "com.fk.jarvis.LISTEN"
        const val ACTION_STOP = "com.fk.jarvis.STOP"

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sesli asistan oturumu", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Asistan dinlerken ya da uyandırma sözcüğü açıkken görünür."
                        setShowBadge(false)
                    }
                )
            }
        }

        /** Yalnızca uygulama öndeyken çağrılmalıdır (Android 14+ mikrofon hizmeti kuralı). */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantSessionService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }
}
