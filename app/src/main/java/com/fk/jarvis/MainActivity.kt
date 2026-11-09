package com.fk.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.avatar.AvatarBridge
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.llm.AIProviderManager
import com.fk.jarvis.service.AssistantSessionService
import com.fk.jarvis.ui.JarvisScreen
import com.fk.jarvis.ui.JarvisTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as JarvisApp
    private val bridge = AvatarBridge()

    /** Mikrofon izni verildikten sonra yapılacak iş (sesli oturum ya da uyandırma sözcüğü). */
    private var afterMicGrant: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterMicGrant
        afterMicGrant = null
        if (granted) next?.invoke() else {
            app.core.onMicDenied()
            if (app.settings.current.wakeWord) app.settings.update { it.copy(wakeWord = false) }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* bildirim izni olmasa da hizmet çalışır; bildirim görünmeyebilir */ }

    private val runtimePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.core.onPermissionResult(granted)
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
                var accessibilityOn by remember { mutableStateOf(app.permissions.isAccessibilityEnabled()) }

                LaunchedEffect(ui.state, ui.micDenied) { bridge.setState(ui.state, ui.micDenied) }
                LaunchedEffect(Unit) { app.core.level.collect { bridge.setLevel(it) } }
                LaunchedEffect(settings.avatarSpeed) { bridge.setSpeed(settings.avatarSpeed) }
                LaunchedEffect(settings.avatarSound) { bridge.setSound(settings.avatarSound) }
                LaunchedEffect(settings.avatarStyle) {
                    com.fk.jarvis.ui.JarvisColors.artStyle = settings.avatarStyle != "neon"
                    bridge.loadStyle(settings.avatarStyle)
                }
                LaunchedEffect(settings.wakeWord) { if (settings.wakeWord) ensureSessionService(forWake = true) }

                JarvisScreen(
                    avatarView = webView,
                    ui = ui,
                    settings = settings,
                    micActive = micActive,
                    conversationActive = conversation,
                    providerConfigured = { id -> app.ai.byId(id)?.isConfigured == true },
                    apiKeySaved = { id -> app.secrets.has(AIProviderManager.keyName(id)) },
                    accessibilityOn = accessibilityOn,
                    onMic = { onMicPressed() },
                    onStopSpeaking = { app.core.endSession() },
                    onSendText = { app.core.submitText(it) },
                    onConfirm = { app.core.confirm(it) },
                    onChooseApp = { app.core.chooseApp(it) },
                    onCancelChoice = { app.core.cancelChoice() },
                    onDismissError = { app.core.dismissError() },
                    onOpenAppSettings = { app.permissions.openAppSettings() },
                    onReplay = { bridge.replay() },
                    onSettingsChange = { t ->
                        app.settings.update(t)
                        app.tts.setRate(app.settings.current.speechRate)
                        app.tts.setPitch(app.settings.current.speechPitch)
                    },
                    onSaveApiKey = { id, key -> app.secrets.put(AIProviderManager.keyName(id), key) },
                    onClearHistory = { app.conversations.clear() },
                    onOpenAccessibility = { app.permissions.openAccessibilitySettings() },
                    onRefreshAccessibility = { accessibilityOn = app.permissions.isAccessibilityEnabled() },
                    onShareDraft = { text -> app.core.process(CommandProposal(CommandIntent.SHARE_TEXT, arguments = mapOf("text" to text))) },
                    onCloseDraft = { app.core.clearDraft() },
                    // İzin, uygulama içi açıklama ekranında kullanıcı "Devam" dedikten sonra sistemden istenir.
                    onGrantPermission = { perm -> runtimePermission.launch(perm) },
                    onDenyPermission = { app.core.onPermissionResult(false) },
                )
            }
        }
    }

    private fun onMicPressed() {
        if (!app.settings.current.consentAccepted) return
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

    /**
     * Ön plan hizmetini başlatır. Android 14+ kuralı gereği yalnızca uygulama öndeyken (buradan) çağrılır.
     * Uyandırma sözcüğü için mikrofon izni ve (Android 13+) bildirim izni istenir.
     */
    private fun ensureSessionService(forWake: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        // Uygulama yeniden açıldığında: uyandırma sözcüğü açıksa hizmeti (öndeyken) yeniden başlat.
        if (app.settings.current.wakeWord && app.permissions.hasMicrophone()) AssistantSessionService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Arka plana geçerken: ön plan hizmeti yoksa (oturum/uyandırma kapalı) mikrofonu kapat.
        val s = app.settings.current
        if (!s.wakeWord && !app.core.conversationActive.value) app.core.cancelListening()
    }
}
