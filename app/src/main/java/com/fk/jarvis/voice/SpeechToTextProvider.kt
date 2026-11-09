package com.fk.jarvis.voice

data class SttConfig(
    val languageTag: String = "tr-TR",
    val silenceTimeoutMs: Int = 1500,
    val partialResults: Boolean = true,
)

enum class SttError { NO_MATCH, NO_SPEECH, PERMISSION, NETWORK, BUSY, UNAVAILABLE, OTHER }

interface SttListener {
    fun onReady() {}
    fun onLevel(level: Float) {}
    fun onPartial(text: String) {}
    fun onFinal(text: String)
    fun onError(error: SttError)
}

/** Değiştirilebilir konuşma-metin sağlayıcısı (Android SpeechRecognizer, bulut, cihaz içi model...). */
interface SpeechToTextProvider {
    val isAvailable: Boolean
    fun start(config: SttConfig, listener: SttListener)
    fun stop()
    fun cancel()
    fun destroy()
}
