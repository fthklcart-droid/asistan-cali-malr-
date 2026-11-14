package com.fk.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Telefonun kendi konuşma tanıyıcısı (Android SpeechRecognizer).
 *
 * Tek bir tanıyıcıya bağlı kalmaz: telefondaki tanıma yollarını bir "strateji" listesi olarak görür
 * (varsayılan hizmet → cihaz içi tanıyıcı → telefonda kurulu diğer Google tanıma hizmetleri). Biri sert hata verirse
 * [nextStrategy] ile sıradakine geçilir; çalışan strateji sonraki turlarda da kullanılır.
 * Her hata, Android'in hata koduyla birlikte [lastErrorText] içinde saklanır (kullanıcıya gösterilir).
 * Tüm çağrılar ana iş parçacığından yapılmalıdır.
 */
class JarvisSpeechRecognizer(
    private val context: Context,
    /** Uyandırma sözcüğü döngüsü için: varsa cihaz içi (internetsiz, daha az pil) tanıyıcı önce denenir. */
    private val preferOnDevice: Boolean = false,
) : SpeechToTextProvider {

    private data class Strategy(val name: String, val onDevice: Boolean = false, val component: ComponentName? = null)

    private var recognizer: SpeechRecognizer? = null
    private var index = 0
    private var exhausted = false

    /** Son hatanın Android kodu ve adı, ör. "ERROR_LANGUAGE_UNAVAILABLE (13)". */
    var lastErrorText: String? = null
        private set
    var lastErrorCode: Int? = null
        private set

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun strategies(): List<Strategy> {
        val list = mutableListOf<Strategy>()
        val onDevice = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        if (preferOnDevice && onDevice) list += Strategy("cihaz içi tanıyıcı", onDevice = true)
        list += Strategy("telefonun varsayılan tanıyıcısı")
        if (!preferOnDevice && onDevice) list += Strategy("cihaz içi tanıyıcı", onDevice = true)
        // Telefonda kurulu, varsayılan OLMAYAN Google tanıma hizmetleri (Google uygulaması, Speech Services by Google).
        val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.let { ComponentName.unflattenFromString(it) }
        runCatching { context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0) }.getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName.startsWith("com.google.") }
            .map { ComponentName(it.packageName, it.name) }
            .filter { it != default }
            .take(2)
            .forEach { list += Strategy(if (it.packageName.contains("tts")) "Google konuşma hizmetleri" else "Google uygulaması tanıyıcısı", component = it) }
        return list
    }

    /** Şu an kullanılan tanıma yolunun adı. */
    val strategyName: String get() = strategies().let { it.getOrNull(index) ?: it.first() }.name

    /** Sıradaki tanıma yoluna geçer; başka yol kalmadıysa false (bir sonraki turda baştan başlanır). */
    fun nextStrategy(): Boolean {
        if (index + 1 < strategies().size) { index++; return true }
        exhausted = true
        return false
    }

    override fun start(config: SttConfig, listener: SttListener) {
        if (!isAvailable) {
            lastErrorText = "telefonda konuşma tanıma hizmeti yok"; lastErrorCode = null
            listener.onError(SttError.UNAVAILABLE)
            return
        }
        // Her oturumda yeni tanıyıcı: bazı cihazlarda tekrar kullanımda ERROR_RECOGNIZER_BUSY oluşuyor.
        destroy()
        val all = strategies()
        if (exhausted || index >= all.size) { index = 0; exhausted = false }
        val s = all[index]
        val r = try {
            when {
                s.onDevice && Build.VERSION.SDK_INT >= 33 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                s.component != null -> SpeechRecognizer.createSpeechRecognizer(context, s.component)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            lastErrorText = "tanıyıcı oluşturulamadı (${e.javaClass.simpleName})"; lastErrorCode = null
            listener.onError(SttError.OTHER)
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private var finished = false
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (finished) return
                finished = true
                lastErrorCode = error
                lastErrorText = "${errorName(error)} ($error)"
                listener.onError(map(error))
            }
            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(SttError.NO_MATCH) else listener.onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceTimeoutMs.toLong())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceTimeoutMs.toLong())
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            lastErrorText = "tanıyıcı başlatılamadı (${e.javaClass.simpleName})"; lastErrorCode = null
            listener.onError(SttError.OTHER)
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    override fun destroy() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        /** Android SpeechRecognizer hata kodlarının adları (API düzeyinden bağımsız olsun diye sayılarla). */
        fun errorName(code: Int): String = when (code) {
            1 -> "ERROR_NETWORK_TIMEOUT"; 2 -> "ERROR_NETWORK"; 3 -> "ERROR_AUDIO"; 4 -> "ERROR_SERVER"; 5 -> "ERROR_CLIENT"
            6 -> "ERROR_SPEECH_TIMEOUT"; 7 -> "ERROR_NO_MATCH"; 8 -> "ERROR_RECOGNIZER_BUSY"; 9 -> "ERROR_INSUFFICIENT_PERMISSIONS"
            10 -> "ERROR_TOO_MANY_REQUESTS"; 11 -> "ERROR_SERVER_DISCONNECTED"; 12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            13 -> "ERROR_LANGUAGE_UNAVAILABLE"; 14 -> "ERROR_CANNOT_CHECK_SUPPORT"; 15 -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_$code"
        }

        fun map(code: Int): SttError = when (code) {
            7 -> SttError.NO_MATCH
            6 -> SttError.NO_SPEECH
            9 -> SttError.PERMISSION
            1, 2, 4 -> SttError.NETWORK
            3 -> SttError.AUDIO
            8, 10 -> SttError.BUSY
            12, 13 -> SttError.LANGUAGE
            else -> SttError.OTHER
        }
    }
}
