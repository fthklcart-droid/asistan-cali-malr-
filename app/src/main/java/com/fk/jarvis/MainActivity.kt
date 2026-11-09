package com.fk.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.avatar.AvatarBridge
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.data.AssistantSettings
import com.fk.jarvis.llm.AIProviderManager
import com.fk.jarvis.llm.LlmException
import com.fk.jarvis.service.AssistantSessionService
import com.fk.jarvis.ui.ConnectionTest
import com.fk.jarvis.ui.HoloActions
import com.fk.jarvis.ui.HoloApp
import com.fk.jarvis.ui.HoloState
import com.fk.jarvis.ui.JarvisColors
import com.fk.jarvis.ui.JarvisTheme
import com.fk.jarvis.ui.SystemStatus
import com.fk.jarvis.voice.TextToSpeechEngine
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private val app get() = application as JarvisApp
    private val bridge = AvatarBridge()

    private var system by mutableStateOf(SystemStatus())
    private var connectionTest by mutableStateOf<ConnectionTest?>(null)
    private var installedApps by mutableStateOf<List<AppInfo>>(emptyList())

    /** Mikrofon izni verildikten sonra yapılacak iş (sesli oturum ya da uyandırma sözcüğü). */
    private var afterMicGrant: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterMicGrant
        afterMicGrant = null
        if (granted) next?.invoke() else {
            app.core.onMicDenied()
            if (app.settings.current.wakeWord) app.settings.update { it.copy(wakeWord = false) }
        }
        refreshSystem()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSystem() }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSystem() }
    /** Çekirdeğin istediği izin (ör. ajanda için takvim): sonuç çekirdeğe bildirilir ve eylem yeniden denenir. */
    private val pendingPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.core.onPermissionResult(granted)
        refreshSystem()
    }

    private val actions = object : HoloActions {
        override fun mic() = onMicPressed()
        override fun endSession() { if (app.live.active.value) app.live.stop() else app.core.endSession() }
        override fun send(text: String) { if (app.live.active.value) app.live.sendText(text) else app.core.submitText(text) }
        override fun confirm(accepted: Boolean) = app.core.confirm(accepted)
        override fun chooseApp(app: AppInfo) = this@MainActivity.app.core.chooseApp(app)
        override fun cancelChoice() = app.core.cancelChoice()
        override fun dismissError() = app.core.dismissError()
        override fun openAppSettings() = app.permissions.openAppSettings()
        override fun replayAvatar() = bridge.replay()
        override fun updateSettings(t: (AssistantSettings) -> AssistantSettings) {
            app.settings.update(t)
            app.tts.setRate(app.settings.current.speechRate)
            app.tts.setPitch(app.settings.current.speechPitch)
        }
        override fun saveKey(providerId: String, key: String?) = app.secrets.put(AIProviderManager.keyName(providerId), key)
        override fun clearHistory() = app.conversations.clear()
        override fun openAccessibility() = app.permissions.openAccessibilitySettings()
        override fun refreshSystem() = this@MainActivity.refreshSystem()
        override fun shareDraft(text: String) = app.core.process(CommandProposal(CommandIntent.SHARE_TEXT, arguments = mapOf("text" to text)))
        override fun closeDraft() = app.core.clearDraft()
        override fun grantPendingPermission(permission: String) = pendingPermission.launch(permission)
        override fun denyPendingPermission() = app.core.onPermissionResult(false)
        override fun testProvider(providerId: String) = runConnectionTest(providerId)
        override fun requestMic() { afterMicGrant = null; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        override fun requestNotifications() {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        override fun requestCalendar() = calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        override fun loadApps() {
            lifecycleScope.launch {
                installedApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.apps.installedApps() }
            }
        }
        override fun launchApp(info: AppInfo) {
            val r = app.apps.launch(info)
            if (!r.success) app.core.speak(r.message)
        }
        override fun openTtsSettings() {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (e: Exception) { app.permissions.openAppSettings() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val webView = createAvatarView()

        setContent {
            JarvisTheme {
                val ui by app.core.ui.collectAsStateWithLifecycle()
                val settings by app.settings.settings.collectAsStateWithLifecycle()
                val micActive by app.voice.micActive.collectAsStateWithLifecycle()
                val conversation by app.core.conversationActive.collectAsStateWithLifecycle()
                val history by app.conversations.entries.collectAsStateWithLifecycle()
                val liveOn by app.live.active.collectAsStateWithLifecycle()

                LaunchedEffect(ui.state, ui.micDenied) { bridge.setState(ui.state, ui.micDenied) }
                LaunchedEffect(Unit) { app.core.level.collect { bridge.setLevel(it) } }
                LaunchedEffect(settings.avatarSpeed) { bridge.setSpeed(settings.avatarSpeed) }
                LaunchedEffect(settings.avatarSound) { bridge.setSound(settings.avatarSound) }
                LaunchedEffect(settings.avatarStyle) {
                    JarvisColors.artStyle = settings.avatarStyle != "neon"
                    bridge.loadStyle(settings.avatarStyle)
                }
                LaunchedEffect(settings.uiAccent, settings.uiAnimations, settings.uiGrid) {
                    JarvisColors.accentChoice = settings.uiAccent
                    com.fk.jarvis.ui.HoloFx.animations = settings.uiAnimations
                    com.fk.jarvis.ui.HoloFx.grid = settings.uiGrid
                }
                LaunchedEffect(settings.wakeWord) { if (settings.wakeWord) ensureSessionService(forWake = true) }

                HoloApp(
                    avatarView = webView,
                    s = HoloState(
                        ui = ui,
                        settings = settings,
                        micActive = micActive,
                        conversation = conversation,
                        level = app.core.level,
                        history = history,
                        system = system,
                        providerReady = { id -> app.ai.byId(id)?.isConfigured == true },
                        keySaved = { id -> app.secrets.has(AIProviderManager.keyName(id)) },
                        connectionTest = connectionTest,
                        liveActive = liveOn,
                        installedApps = installedApps,
                    ),
                    a = actions,
                )
            }
        }
    }

    private fun onMicPressed() {
        if (!app.settings.current.consentAccepted) return
        // Gerçek zamanlı mod: KONUŞ canlı Gemini oturumunu açar/kapatır.
        if (app.settings.current.liveMode || app.live.active.value) {
            if (app.live.active.value) { app.live.stop(); return }
            withMic {
                val err = app.live.start()
                if (err != null) app.core.speak(err) else ensureSessionService(forWake = false)
            }
            return
        }
        val core = app.core
        when (core.ui.value.state) {
            AssistantState.LISTENING -> core.stopListening()
            else -> withMic {
                core.startVoiceSession()
                if (app.settings.current.conversationMode) ensureSessionService(forWake = false)
            }
        }
    }

    private fun withMic(block: () -> Unit) {
        if (app.permissions.hasMicrophone()) block() else {
            afterMicGrant = block
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Sağlayıcıya gerçek, kısa bir istek gönderip sonucu ve süreyi gösterir. */
    private fun runConnectionTest(id: String) {
        val provider = app.ai.byId(id) ?: return
        connectionTest = ConnectionTest(id, running = true, ok = null, message = "Test ediliyor…", ms = null)
        lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            connectionTest = try {
                val r = provider.propose(
                    "Bu bir bağlantı testidir. intent=chat ve spoken_response='Bağlantı başarılı.' döndür.",
                    emptyList(), emptyMap(),
                )
                ConnectionTest(id, false, true, "Yanıt: " + r.spokenResponse.ifBlank { "(boş)" }.take(160), SystemClock.elapsedRealtime() - t0)
            } catch (e: LlmException) {
                ConnectionTest(id, false, false, e.userMessage(), SystemClock.elapsedRealtime() - t0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTest(id, false, false, "Beklenmeyen hata: ${e.javaClass.simpleName}", SystemClock.elapsedRealtime() - t0)
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Sistem ekranındaki tüm durumları cihazdan okur. */
    private fun refreshSystem() {
        val info = try {
            if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) { null }
        val sig = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners?.firstOrNull()
        else @Suppress("DEPRECATION") info?.signatures?.firstOrNull()
        val sha = sig?.toByteArray()?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString(":") { "%02X".format(it) } } ?: "—"
        val tts = app.tts.status
        system = SystemStatus(
            micGranted = app.permissions.hasMicrophone(),
            notificationsGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            calendarGranted = granted(Manifest.permission.READ_CALENDAR),
            accessibilityOn = app.permissions.isAccessibilityEnabled(),
            screenControlBuild = BuildConfig.SCREEN_CONTROL,
            sttAvailable = SpeechRecognizer.isRecognitionAvailable(this),
            ttsStatus = when (tts) {
                TextToSpeechEngine.Status.READY -> "Türkçe ses hazır"
                TextToSpeechEngine.Status.INITIALIZING -> "Başlatılıyor…"
                TextToSpeechEngine.Status.LANGUAGE_MISSING -> "Türkçe ses verisi yok — metin okuma ayarlarından indir"
                TextToSpeechEngine.Status.FAILED -> "Metin okuma motoru başlatılamadı"
            },
            ttsReady = tts == TextToSpeechEngine.Status.READY,
            serviceRunning = AssistantSessionService.running,
            versionName = info?.versionName ?: "—",
            versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0,
            signatureSha256 = sha,
            testSigned = sha.startsWith(TEST_KEY_SHA_PREFIX),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    /**
     * Ön plan hizmetini başlatır. Android 14+ kuralı gereği yalnızca uygulama öndeyken (buradan) çağrılır.
     * Uyandırma sözcüğü için mikrofon izni ve (Android 13+) bildirim izni istenir.
     */
    private fun ensureSessionService(forWake: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (forWake && !app.permissions.hasMicrophone()) {
            afterMicGrant = { AssistantSessionService.start(this) }
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        AssistantSessionService.start(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAvatarView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        bridge.attach(this)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                bridge.onPageLoaded()
                bridge.setSpeed(app.settings.current.avatarSpeed)
                bridge.setSound(app.settings.current.avatarSound)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystem()
        // Uygulama yeniden açıldığında: uyandırma sözcüğü açıksa hizmeti (öndeyken) yeniden başlat.
        if (app.settings.current.wakeWord && app.permissions.hasMicrophone()) AssistantSessionService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Live oturumu arka planda ön plan hizmetiyle sürer; hizmet yoksa kapatılır.
        if (app.live.active.value && !AssistantSessionService.running) app.live.stop()
        // Arka plana geçerken: ön plan hizmeti yoksa (oturum/uyandırma kapalı) mikrofonu kapat.
        val s = app.settings.current
        if (!s.wakeWord && !app.core.conversationActive.value) app.core.cancelListening()
    }

    companion object {
        /** Depodaki herkese açık test anahtarının (signing/sirius-test.jks) SHA-256 başlangıcı. */
        const val TEST_KEY_SHA_PREFIX = "E3:33:D6:D4:C5:21"
    }
}
