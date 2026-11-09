package com.fk.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android'in yerleşik SpeechRecognizer'ı ile Türkçe STT.
 * Tanıma hizmeti cihaza göre değişir (çoğunlukla Google uygulaması); bazı cihazlarda internet gerekir.
 * Tüm çağrılar ana iş parçacığından yapılmalıdır.
 */
class AndroidSpeechToText(private val context: Context) : SpeechToTextProvider {
    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(config: SttConfig, listener: SttListener) {
        if (!isAvailable) {
            listener.onError(SttError.UNAVAILABLE)
            return
        }
        // Her oturumda yeni tanıyıcı: bazı cihazlarda tekrar kullanımda ERROR_RECOGNIZER_BUSY oluşuyor.
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
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
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_MATCH
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NO_SPEECH
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PERMISSION
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> SttError.NETWORK
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.BUSY
                        else -> SttError.OTHER
                    }
                )
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
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }
}
