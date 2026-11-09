package com.fk.jarvis

import android.app.Application
import com.fk.jarvis.apps.AndroidAppController
import com.fk.jarvis.apps.CalendarReader
import com.fk.jarvis.core.AssistantCore
import com.fk.jarvis.core.CommandRouter
import com.fk.jarvis.data.ConversationManager
import com.fk.jarvis.data.SecureKeyStore
import com.fk.jarvis.data.SettingsRepository
import com.fk.jarvis.llm.AIProviderManager
import com.fk.jarvis.llm.ClaudeAIService
import com.fk.jarvis.llm.GeminiAIService
import com.fk.jarvis.llm.LLMProvider
import com.fk.jarvis.llm.OpenAICompatibleService
import com.fk.jarvis.permissions.PermissionManager
import com.fk.jarvis.voice.JarvisSpeechRecognizer
import com.fk.jarvis.voice.MicBargeInMonitor
import com.fk.jarvis.voice.SttConfig
import com.fk.jarvis.voice.TextToSpeechEngine
import com.fk.jarvis.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Uygulama genelinde paylaşılan bileşenler. Activity, ön plan hizmeti ve erişilebilirlik hizmeti
 * aynı [AssistantCore]'u kullanır; uygulama yeniden açıldığında oturum ve geçmiş buradan sürer.
 */
class JarvisApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsRepository
        private set
    lateinit var secrets: SecureKeyStore
        private set
    lateinit var conversations: ConversationManager
        private set
    lateinit var permissions: PermissionManager
        private set
    lateinit var voice: VoiceEngine
        private set
    lateinit var tts: TextToSpeechEngine
        private set
    lateinit var apps: AndroidAppController
        private set
    lateinit var ai: AIProviderManager
        private set
    lateinit var core: AssistantCore
        private set
    lateinit var live: com.fk.jarvis.live.LiveController
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        secrets = SecureKeyStore(this)
        conversations = ConversationManager(this) { settings.current.keepHistory }
        permissions = PermissionManager(this)
        voice = VoiceEngine(JarvisSpeechRecognizer(this), permissions)
        tts = TextToSpeechEngine(this).also {
            it.setRate(settings.current.speechRate)
            it.setPitch(settings.current.speechPitch)
        }
        apps = AndroidAppController(this)

        val name = { settings.current.assistantName }
        fun key(id: String): () -> String? = { secrets.get(AIProviderManager.keyName(id)) }
        fun model(id: String): () -> String = { settings.current.modelFor(id) }
        fun base(id: String): () -> String? = { settings.current.baseUrlFor(id) }
        ai = AIProviderManager(
            selectedId = { settings.current.provider },
            providers = listOf(
                GeminiAIService(key("gemini"), model("gemini"), name, base("gemini")),
                OpenAICompatibleService(OpenAICompatibleService.Mode.OPENAI, key("openai"), model("openai"), base("openai"), name),
                ClaudeAIService(key("claude"), model("claude"), base("claude"), name),
                OpenAICompatibleService(OpenAICompatibleService.Mode.LOCAL, key("local"), model("local"), base("local"), name),
            ),
        )

        core = AssistantCore(
            scope = appScope,
            input = voice,
            output = tts,
            history = conversations,
            executor = CommandRouter(apps, CalendarReader(this)),
            llm = { ai.current() },
            sttConfig = { SttConfig(languageTag = "tr-TR", silenceTimeoutMs = settings.current.silenceTimeoutMs) },
            listenAfterQuestion = { settings.current.listenAfterQuestion },
            conversationMode = { settings.current.conversationMode },
            bargeInEnabled = { settings.current.bargeIn },
            bargeIn = MicBargeInMonitor(permissions),
        )
        tts.onLevel = { core.onTtsLevel(it) }
        live = com.fk.jarvis.live.LiveController(
            core = core,
            permissions = permissions,
            apiKey = { secrets.get(AIProviderManager.keyName("gemini")) },
            model = { settings.current.liveModel },
            voice = { settings.current.liveVoice },
            baseUrl = { settings.current.baseUrlFor("gemini") },
            assistantName = { settings.current.assistantName },
            bargeIn = { settings.current.bargeIn },
        )
    }

    fun currentProvider(): LLMProvider = ai.current()
}
