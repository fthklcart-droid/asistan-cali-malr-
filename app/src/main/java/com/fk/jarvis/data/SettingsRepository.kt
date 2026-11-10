package com.fk.jarvis.data

import android.content.Context
import com.fk.jarvis.llm.AIProviderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantSettings(
    val assistantName: String = "JARVIS",
    /** "gemini", "openai", "claude", "local" veya "none" (yalnızca yerel komutlar). */
    val provider: String = "gemini",
    /** Sağlayıcı kimliği -> model adı (boşsa sağlayıcının varsayılanı). */
    val models: Map<String, String> = emptyMap(),
    /** Sağlayıcı kimliği -> özel sunucu adresi (proxy ya da yerel model sunucusu). */
    val baseUrls: Map<String, String> = emptyMap(),
    val silenceTimeoutMs: Int = 1500,
    val listenAfterQuestion: Boolean = true,
    /** Kesintisiz sohbet: yanıttan sonra otomatik olarak yeniden dinle. */
    val conversationMode: Boolean = true,
    /** Asistan konuşurken kullanıcı konuşursa yanıtı kes ve dinle. */
    val bargeIn: Boolean = false,
    /** "JARVIS" uyandırma sözcüğü (ön plan hizmeti + bildirim ile, yalnızca kullanıcı açarsa). */
    val wakeWord: Boolean = false,
    /** Ekran kapalıyken uyandırma sözcüğü dinlensin mi (pil tüketimi). */
    val wakeWhileScreenOff: Boolean = false,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val keepHistory: Boolean = true,
    val avatarSpeed: Float = 1.0f,
    val avatarSound: Boolean = true,
    /** "art" (siyah zemin, neon mavi holografik büst) veya "neon" (mor neon figür). */
    val avatarStyle: String = "art",
    /** İlk açılışta gizlilik ve izin bilgilendirmesini kabul etti mi (Google Play "açık bilgilendirme" kuralı). */
    val consentAccepted: Boolean = false,
    /** Gerçek zamanlı sesli konuşma: Gemini Live (KONUŞ düğmesi canlı oturum açar). */
    val liveMode: Boolean = false,
    val liveModel: String = com.fk.jarvis.live.GeminiLiveProtocol.DEFAULT_MODEL,
    val liveVoice: String = "Puck",
    /** Arayüz vurgu rengi: "auto" (avatara göre), "cyan", "violet", "gold", "green". */
    val uiAccent: String = "auto",
    /** Holografik geçiş ve giriş animasyonları. */
    val uiAnimations: Boolean = true,
    /** Arka plan ızgarası ve tarama çizgisi. */
    val uiGrid: Boolean = true,
    /** Avatarların üstünde 3B holografik katman (yörünge halkaları, projektör, eğilme). */
    val avatarHolo3d: Boolean = true,
    /** Kalıcı hafızanın kısa özeti yapay zekâ isteklerine bağlam olarak eklensin mi. */
    val memoryEnabled: Boolean = true,
) {
    fun modelFor(id: String) = models[id]?.takeIf { it.isNotBlank() } ?: AIProviderManager.defaultModel(id)
    fun baseUrlFor(id: String) = baseUrls[id]?.takeIf { it.isNotBlank() }
}

/** Kullanıcı tercihleri (hassas olmayan). API anahtarları burada değil, [SecureKeyStore]'dadır. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val ids = AIProviderManager.choices.map { it.first }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AssistantSettings> = _settings.asStateFlow()

    val current: AssistantSettings get() = _settings.value

    private fun load(): AssistantSettings {
        val d = AssistantSettings()
        return AssistantSettings(
            assistantName = prefs.getString("name", null) ?: d.assistantName,
            provider = prefs.getString("provider", null) ?: d.provider,
            models = ids.mapNotNull { id -> prefs.getString("model_$id", null)?.let { id to it } }.toMap(),
            baseUrls = ids.mapNotNull { id -> prefs.getString("base_$id", null)?.let { id to it } }.toMap(),
            silenceTimeoutMs = prefs.getInt("silence_ms", d.silenceTimeoutMs),
            listenAfterQuestion = prefs.getBoolean("listen_after_q", d.listenAfterQuestion),
            conversationMode = prefs.getBoolean("conversation_mode", d.conversationMode),
            bargeIn = prefs.getBoolean("barge_in", d.bargeIn),
            wakeWord = prefs.getBoolean("wake_word", d.wakeWord),
            wakeWhileScreenOff = prefs.getBoolean("wake_screen_off", d.wakeWhileScreenOff),
            speechRate = prefs.getFloat("speech_rate", d.speechRate),
            speechPitch = prefs.getFloat("speech_pitch", d.speechPitch),
            keepHistory = prefs.getBoolean("keep_history", d.keepHistory),
            avatarSpeed = prefs.getFloat("avatar_speed", d.avatarSpeed),
            avatarSound = prefs.getBoolean("avatar_sound", d.avatarSound),
            avatarStyle = prefs.getString("avatar_style", null) ?: d.avatarStyle,
            consentAccepted = prefs.getBoolean("consent_v1", d.consentAccepted),
            liveMode = prefs.getBoolean("live_mode", d.liveMode),
            liveModel = prefs.getString("live_model", null) ?: d.liveModel,
            liveVoice = prefs.getString("live_voice", null) ?: d.liveVoice,
            uiAccent = prefs.getString("ui_accent", null) ?: d.uiAccent,
            uiAnimations = prefs.getBoolean("ui_anim", d.uiAnimations),
            uiGrid = prefs.getBoolean("ui_grid", d.uiGrid),
            avatarHolo3d = prefs.getBoolean("avatar_holo3d", d.avatarHolo3d),
            memoryEnabled = prefs.getBoolean("memory_enabled", d.memoryEnabled),
        )
    }

    fun update(transform: (AssistantSettings) -> AssistantSettings) {
        val s = transform(_settings.value)
        val e = prefs.edit()
            .putString("name", s.assistantName)
            .putString("provider", s.provider)
            .putInt("silence_ms", s.silenceTimeoutMs.coerceIn(500, 10_000))
            .putBoolean("listen_after_q", s.listenAfterQuestion)
            .putBoolean("conversation_mode", s.conversationMode)
            .putBoolean("barge_in", s.bargeIn)
            .putBoolean("wake_word", s.wakeWord)
            .putBoolean("wake_screen_off", s.wakeWhileScreenOff)
            .putFloat("speech_rate", s.speechRate.coerceIn(0.5f, 2f))
            .putFloat("speech_pitch", s.speechPitch.coerceIn(0.5f, 2f))
            .putBoolean("keep_history", s.keepHistory)
            .putFloat("avatar_speed", s.avatarSpeed)
            .putBoolean("avatar_sound", s.avatarSound)
            .putString("avatar_style", if (s.avatarStyle == "neon") "neon" else "art")
            .putBoolean("consent_v1", s.consentAccepted)
            .putBoolean("live_mode", s.liveMode)
            .putString("live_model", s.liveModel)
            .putString("live_voice", s.liveVoice)
            .putString("ui_accent", s.uiAccent)
            .putBoolean("ui_anim", s.uiAnimations)
            .putBoolean("ui_grid", s.uiGrid)
            .putBoolean("avatar_holo3d", s.avatarHolo3d)
            .putBoolean("memory_enabled", s.memoryEnabled)
        for (id in ids) {
            val m = s.models[id]
            if (m != null) e.putString("model_$id", m) else e.remove("model_$id")
            val b = s.baseUrls[id]
            if (b != null) e.putString("base_$id", b) else e.remove("base_$id")
        }
        e.apply()
        _settings.value = s
    }
}
