package com.fk.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.fk.jarvis.core.SpeechOutput
import com.fk.jarvis.core.TextNormalizer
import java.util.UUID

/**
 * Android TextToSpeech ile Türkçe seslendirme.
 * [onLevel] gerçek ses genliği DEĞİLDİR: TTS motoru genlik vermez; kelime başlangıç
 * zamanlarından (onRangeStart) türetilen bir konuşma ritmi sinyalidir. Avatar ağız/ışık animasyonu için kullanılır.
 */
class TextToSpeechManager(context: Context) : SpeechOutput {
    enum class Status { INITIALIZING, READY, LANGUAGE_MISSING, FAILED }

    var status: Status = Status.INITIALIZING
        private set

    var onLevel: ((Float) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private val pending = mutableListOf<Pair<String, () -> Unit>>()
    private var rate = 1.0f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code ->
        main.post { onInit(code) }
    }

    private fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) {
            status = Status.FAILED
        } else {
            val r = tts.setLanguage(TextNormalizer.TR)
            status = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Status.LANGUAGE_MISSING else Status.READY
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { onLevel?.invoke(0.6f) } }
                override fun onDone(utteranceId: String?) { main.post { finish(utteranceId) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { finish(utteranceId) } }
                override fun onError(utteranceId: String?, errorCode: Int) { main.post { finish(utteranceId) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { finish(utteranceId) } }
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    main.post { onLevel?.invoke((0.55f + (end - start).coerceAtMost(10) * 0.045f).coerceAtMost(1f)) }
                }
            })
        }
        val queued = pending.toList()
        pending.clear()
        queued.forEach { (text, done) -> speak(text, done) }
    }

    private fun finish(id: String?) {
        onLevel?.invoke(0f)
        id?.let { callbacks.remove(it)?.invoke() }
    }

    fun setRate(r: Float) {
        rate = r
        if (status == Status.READY) tts.setSpeechRate(r)
    }

    /** Metni seslendirir; bitince (veya seslendirilemezse hemen) [onDone] çağrılır. */
    override fun speak(text: String, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }
        when (status) {
            Status.INITIALIZING -> pending += text to onDone
            Status.READY -> {
                val id = UUID.randomUUID().toString()
                callbacks[id] = onDone
                val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (res != TextToSpeech.SUCCESS) finish(id)
            }
            else -> onDone()
        }
    }

    override fun stop() {
        if (status == Status.READY) tts.stop()
        val cbs = callbacks.values.toList()
        callbacks.clear()
        onLevel?.invoke(0f)
        cbs.forEach { it() }
    }

    val isSpeaking: Boolean get() = status == Status.READY && tts.isSpeaking

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
