package com.fk.jarvis.voice

import com.fk.jarvis.core.MicStartResult
import com.fk.jarvis.core.SpeechInput
import com.fk.jarvis.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mikrofon oturumlarını yönetir. Mikrofon yalnızca açık kullanıcı etkileşimiyle
 * (düğme) ya da asistanın sorduğu soruya yanıt için kısa süreli açılır; sürekli dinleme yoktur.
 */
class VoiceEngine(
    private val provider: SpeechToTextProvider,
    private val permissions: PermissionManager,
) : SpeechInput {
    private val _micActive = MutableStateFlow(false)
    /** Arayüzdeki görünür mikrofon göstergesi bu akışa bağlıdır. */
    val micActive: StateFlow<Boolean> = _micActive.asStateFlow()

    override fun start(config: SttConfig, listener: SttListener): MicStartResult {
        if (!permissions.hasMicrophone()) return MicStartResult.NoPermission
        if (!provider.isAvailable) return MicStartResult.Unavailable
        _micActive.value = true
        provider.start(config, object : SttListener {
            override fun onReady() = listener.onReady()
            override fun onLevel(level: Float) = listener.onLevel(level)
            override fun onPartial(text: String) = listener.onPartial(text)
            override fun onFinal(text: String) {
                _micActive.value = false
                listener.onFinal(text)
            }
            override fun onError(error: SttError) {
                _micActive.value = false
                listener.onError(error)
            }
        })
        return MicStartResult.Started
    }

    /** Kullanıcı konuşmayı bitirdi: eldeki sesi işle. */
    override fun stop() = provider.stop()

    /** Oturumu sonuçsuz kapat. */
    override fun cancel() {
        provider.cancel()
        _micActive.value = false
    }

    fun release() {
        provider.destroy()
        _micActive.value = false
    }
}
