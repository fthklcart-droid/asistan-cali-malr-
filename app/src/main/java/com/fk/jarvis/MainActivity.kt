package com.fk.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.avatar.AvatarBridge
import com.fk.jarvis.ui.JarvisScreen
import com.fk.jarvis.ui.JarvisTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as JarvisApp
    private val bridge = AvatarBridge()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.orchestrator.startListening() else app.orchestrator.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val webView = createAvatarView()

        setContent {
            JarvisTheme {
                val ui by app.orchestrator.ui.collectAsStateWithLifecycle()
                val settings by app.settings.settings.collectAsStateWithLifecycle()
                val micActive by app.voiceInput.micActive.collectAsStateWithLifecycle()
                var accessibilityOn by remember { mutableStateOf(app.permissions.isAccessibilityEnabled()) }

                LaunchedEffect(ui.state, ui.micDenied) { bridge.setState(ui.state, ui.micDenied) }
                LaunchedEffect(Unit) { app.orchestrator.level.collect { bridge.setLevel(it) } }
                LaunchedEffect(settings.avatarSpeed) { bridge.setSpeed(settings.avatarSpeed) }
                LaunchedEffect(settings.avatarSound) { bridge.setSound(settings.avatarSound) }
                LaunchedEffect(settings.avatarStyle) { bridge.loadStyle(settings.avatarStyle) }

                JarvisScreen(
                    avatarView = webView,
                    ui = ui,
                    settings = settings,
                    micActive = micActive,
                    cloudConfigured = app.currentProvider().isConfigured,
                    apiKeySaved = { app.secrets.has(com.fk.jarvis.data.SecureKeyStore.GEMINI_API_KEY) },
                    accessibilityOn = accessibilityOn,
                    onMic = { onMicPressed() },
                    onStopSpeaking = { app.orchestrator.stopSpeaking() },
                    onSendText = { app.orchestrator.submitText(it) },
                    onConfirm = { app.orchestrator.confirm(it) },
                    onChooseApp = { app.orchestrator.chooseApp(it) },
                    onCancelChoice = { app.orchestrator.cancelChoice() },
                    onDismissError = { app.orchestrator.dismissError() },
                    onOpenAppSettings = { app.permissions.openAppSettings() },
                    onReplay = { bridge.replay() },
                    onSettingsChange = { t -> app.settings.update(t); app.tts.setRate(app.settings.current.speechRate) },
                    onSaveApiKey = { app.secrets.put(com.fk.jarvis.data.SecureKeyStore.GEMINI_API_KEY, it) },
                    onClearHistory = { app.conversations.clear() },
                    onOpenAccessibility = { app.permissions.openAccessibilitySettings() },
                    onRefreshAccessibility = { accessibilityOn = app.permissions.isAccessibilityEnabled() },
                )
            }
        }
    }

    private fun onMicPressed() {
        val o = app.orchestrator
        when (o.ui.value.state) {
            com.fk.jarvis.core.AssistantState.LISTENING -> o.stopListening()
            else -> if (app.permissions.hasMicrophone()) o.startListening()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAvatarView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.rgb(7, 2, 15))
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

    override fun onStop() {
        super.onStop()
        // Uygulama arka plana geçince mikrofon kapanır (erişilebilirlik düğmesiyle başlatılan oturumlar hariç).
        if (app.permissions.isAccessibilityEnabled().not()) app.orchestrator.cancelListening()
    }
}
