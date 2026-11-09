package com.fk.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.fk.jarvis.core.SpeechOutput
import com.fk.jarvis.core.TextNormalizer
import java.io.File
import java.util.UUID

/**
 * Android TextToSpeech ile Türkçe seslendirme + dudak senkronu.
 *
 * Dudak senkronu: metin önce bir WAV dosyasına sentezlenir, dosyadan ses zarfı ([WavEnvelope]) çıkarılır,
 * dosya MediaPlayer ile çalınırken oynatma konumuna karşılık gelen zarf değeri [onLevel] ile avatara verilir.
 * Bu, fonem/vizem tabanlı değil, gerçek ses genliğine dayalı bir senkrondur.
 * Sentez ya da çözümleme başarısız olursa doğrudan konuşmaya düşülür; o durumda seviye kelime
 * zamanlamasından (onRangeStart) türetilir.
 */
class TextToSpeechEngine(context: Context) : SpeechOutput {
    enum class Status { INITIALIZING, READY, LANGUAGE_MISSING, FAILED }

    var status: Status = Status.INITIALIZING
        private set

    /** 0..1 konuşma seviyesi (avatar ağız/ışık animasyonu). */
    var onLevel: ((Float) -> Unit)? = null
    /** Ses fiilen çalmaya başladığında (barge-in dinleyicisini başlatmak için). */
    var onAudioStarted: (() -> Unit)? = null

    var lipSync: Boolean = true

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Pair<String, () -> Unit>>()
    private var rate = 1.0f
    private var pitch = 1.0f

    private var current: Utterance? = null

    private class Utterance(val id: String, val text: String, val onDone: () -> Unit) {
        var file: File? = null
        var player: MediaPlayer? = null
        var envelope: WavEnvelope.Envelope? = null
        var finished = false
    }

    private val tts: TextToSpeech = TextToSpeech(appContext) { code -> main.post { onInit(code) } }

    private fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) {
            status = Status.FAILED
        } else {
            val r = tts.setLanguage(TextNormalizer.TR)
            status = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Status.LANGUAGE_MISSING else Status.READY
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    main.post { if (isDirect(utteranceId)) { onLevel?.invoke(0.6f); onAudioStarted?.invoke() } }
                }
                override fun onDone(utteranceId: String?) { main.post { onEngineDone(utteranceId, ok = true) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { onEngineDone(utteranceId, ok = false) } }
                override fun onError(utteranceId: String?, errorCode: Int) { main.post { onEngineDone(utteranceId, ok = false) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { if (isDirect(utteranceId)) finish(current) } }
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    main.post { if (isDirect(utteranceId)) onLevel?.invoke((0.55f + (end - start).coerceAtMost(10) * 0.045f).coerceAtMost(1f)) }
                }
            })
        }
        val queued = pending.toList()
        pending.clear()
        queued.forEach { (text, done) -> speak(text, done) }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun isDirect(id: String?) = id != null && id == current?.id && current?.file == null
    private fun isSynth(id: String?) = id != null && id == current?.id && current?.file != null

    fun setRate(r: Float) { rate = r; if (status == Status.READY) tts.setSpeechRate(r) }
    fun setPitch(p: Float) { pitch = p; if (status == Status.READY) tts.setPitch(p) }

    /** Metni seslendirir; bitince (veya seslendirilemezse hemen) [onDone] çağrılır. */
    override fun speak(text: String, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }
        when (status) {
            Status.INITIALIZING -> pending += text to onDone
            Status.READY -> {
                stopInternal(notify = true)
                val u = Utterance(UUID.randomUUID().toString(), text, onDone)
                current = u
                if (lipSync) synthesize(u) else direct(u)
            }
            else -> onDone()
        }
    }

    private fun synthesize(u: Utterance) {
        val f = File(appContext.cacheDir, "tts_${u.id}.wav")
        u.file = f
        val res = tts.synthesizeToFile(u.text, Bundle(), f, u.id)
        if (res != TextToSpeech.SUCCESS) { u.file = null; f.delete(); direct(u) }
    }

    private fun direct(u: Utterance) {
        val res = tts.speak(u.text, TextToSpeech.QUEUE_FLUSH, Bundle(), u.id)
        if (res != TextToSpeech.SUCCESS) finish(u)
    }

    private fun onEngineDone(id: String?, ok: Boolean) {
        val u = current ?: return
        if (u.id != id) return
        if (isSynth(id)) {
            if (ok && play(u)) return
            // Dosyadan çalınamadı: doğrudan konuşmaya düş.
            u.file?.delete(); u.file = null
            direct(u)
        } else {
            finish(u)
        }
    }

    private fun play(u: Utterance): Boolean {
        val f = u.file ?: return false
        return try {
            u.envelope = WavEnvelope.compute(f.readBytes())
            val mp = MediaPlayer()
            mp.setAudioAttributes(attributes)
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { finish(u) }
            mp.setOnErrorListener { _, _, _ -> finish(u); true }
            mp.prepare()
            u.player = mp
            mp.start()
            onAudioStarted?.invoke()
            main.post(ticker)
            true
        } catch (e: Exception) {
            u.player?.release(); u.player = null
            false
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val u = current ?: return
            val mp = u.player ?: return
            if (u.finished) return
            val pos = try { mp.currentPosition } catch (e: IllegalStateException) { return }
            onLevel?.invoke(u.envelope?.at(pos) ?: 0.5f)
            main.postDelayed(this, 33)
        }
    }

    private fun finish(u: Utterance?) {
        if (u == null || u.finished) return
        u.finished = true
        main.removeCallbacks(ticker)
        u.player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        u.player = null
        u.file?.delete()
        if (current === u) current = null
        onLevel?.invoke(0f)
        u.onDone()
    }

    private fun stopInternal(notify: Boolean) {
        val u = current ?: return
        if (status == Status.READY) tts.stop()
        if (notify) finish(u) else { u.finished = true; current = null }
    }

    override fun stop() {
        pending.clear()
        stopInternal(notify = true)
    }

    val isSpeaking: Boolean get() = current != null

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
