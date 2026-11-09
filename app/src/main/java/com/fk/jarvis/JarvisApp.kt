package com.fk.jarvis

import android.app.Application
import com.fk.jarvis.apps.AndroidAppController
import com.fk.jarvis.core.AssistantOrchestrator
import com.fk.jarvis.core.CommandRouter
import com.fk.jarvis.data.ConversationRepository
import com.fk.jarvis.data.SecureKeyStore
import com.fk.jarvis.data.SettingsRepository
import com.fk.jarvis.llm.GeminiProvider
import com.fk.jarvis.llm.LLMProvider
import com.fk.jarvis.llm.NoLLMProvider
import com.fk.jarvis.permissions.PermissionManager
import com.fk.jarvis.voice.AndroidSpeechToText
import com.fk.jarvis.voice.SttConfig
import com.fk.jarvis.voice.TextToSpeechManager
import com.fk.jarvis.voice.VoiceInputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Uygulama genelinde paylaşılan bileşenler (Activity ve erişilebilirlik hizmeti aynı orkestratörü kullanır). */
class JarvisApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsRepository
        private set
    lateinit var secrets: SecureKeyStore
        private set
    lateinit var conversations: ConversationRepository
        private set
    lateinit var permissions: PermissionManager
        private set
    lateinit var voiceInput: VoiceInputManager
        private set
    lateinit var tts: TextToSpeechManager
        private set
    lateinit var apps: AndroidAppController
        private set
    lateinit var orchestrator: AssistantOrchestrator
        private set

    private lateinit var gemini: GeminiProvider

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        secrets = SecureKeyStore(this)
        conversations = ConversationRepository(this) { settings.current.keepHistory }
        permissions = PermissionManager(this)
        voiceInput = VoiceInputManager(AndroidSpeechToText(this), permissions)
        tts = TextToSpeechManager(this).also { it.setRate(settings.current.speechRate) }
        apps = AndroidAppController(this)
        gemini = GeminiProvider(
            apiKeyProvider = { secrets.get(SecureKeyStore.GEMINI_API_KEY) },
            modelProvider = { settings.current.geminiModel },
            assistantName = { settings.current.assistantName },
        )
        orchestrator = AssistantOrchestrator(
            scope = appScope,
            input = voiceInput,
            output = tts,
            history = conversations,
            executor = CommandRouter(apps),
            llm = { currentProvider() },
            sttConfig = { SttConfig(languageTag = "tr-TR", silenceTimeoutMs = settings.current.silenceTimeoutMs) },
            listenAfterQuestion = { settings.current.listenAfterQuestion },
        )
        tts.onLevel = { orchestrator.onTtsLevel(it) }
    }

    fun currentProvider(): LLMProvider = when (settings.current.provider) {
        "gemini" -> gemini
        else -> NoLLMProvider
    }
}
