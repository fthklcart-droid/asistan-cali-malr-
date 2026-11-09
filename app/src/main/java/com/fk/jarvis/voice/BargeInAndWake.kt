package com.fk.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.fk.jarvis.core.BargeInMonitor
import com.fk.jarvis.permissions.PermissionManager

/**
 * Asistan konuşurken kullanıcının konuşmaya başladığını algılar (barge-in).
 * Mikrofonu VOICE_COMMUNICATION kaynağıyla ve (cihaz destekliyorsa) yankı giderici ile açar; böylece
 * asistanın kendi sesinin tetiklemesi azaltılır. Yalnızca asistan konuşurken ve ayar açıksa çalışır.
 * Hoparlör sesinin yüksek olduğu cihazlarda yanlış tetikleme olabilir — ayarlardan kapatılabilir.
 */
class MicBargeInMonitor(private val permissions: PermissionManager) : BargeInMonitor {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onSpeech: () -> Unit): Boolean {
        if (running || !permissions.hasMicrophone()) return false
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } else null
        running = true
        thread = Thread({
            val gate = VadGate()
            val buf = ShortArray(rate / 50) // 20 ms
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (gate.offer(VadGate.levelDb(buf, n))) {
                        running = false
                        main.post(onSpeech)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release(); aec?.release(); ns?.release()
            }
        }, "jarvis-bargein").also { it.start() }
        return true
    }

    override fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }
}

/**
 * "JARVIS" uyandırma sözcüğü döngüsü. Yalnızca kullanıcı ayarlardan açtığında, ön plan hizmeti
 * (bildirimli) içinde çalışır. Her turda kısa bir tanıma oturumu açar; kısmi sonuçlarda sözcük
 * duyulursa döngüyü durdurur ve [onWake] ile ardından gelen komutu (boş olabilir) bildirir.
 * Not: Bu, ayrı bir anahtar sözcük modeli değildir; sistem konuşma tanıyıcısını tekrar tekrar kullanır,
 * bu yüzden pil tüketir ve bazı cihazlarda her turda kısa bir ses çalabilir.
 */
class WakeWordLoop(
    private val stt: SpeechToTextProvider,
    private val onWake: (command: String) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    var active = false
        private set
    private var backoffMs = 300L

    fun start() {
        if (active) return
        active = true
        backoffMs = 300
        listenOnce()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        stt.cancel()
    }

    private fun listenOnce() {
        if (!active) return
        if (!stt.isAvailable) { active = false; onUnavailable(); return }
        var heard = false
        stt.start(SttConfig(silenceTimeoutMs = 1200), object : SttListener {
            // Kısmi sonuçta sözcük duyulsa bile komutun tamamı için cümlenin bitmesi beklenir.
            override fun onPartial(text: String) { if (WakeWordDetector.detect(text) != null) heard = true }
            override fun onFinal(text: String) {
                if (!active) return
                val hit = WakeWordDetector.detect(text)
                if (hit != null || heard) fire(hit?.command.orEmpty()) else again(300)
            }
            override fun onError(error: SttError) {
                when (error) {
                    SttError.PERMISSION, SttError.UNAVAILABLE -> { active = false; onUnavailable() }
                    SttError.BUSY, SttError.NETWORK, SttError.OTHER -> again(backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(15_000) })
                    else -> { backoffMs = 300; again(250) }
                }
            }
        })
    }

    private fun fire(command: String) {
        active = false
        onWake(command)
    }

    private fun again(delayMs: Long) {
        if (!active) return
        main.postDelayed({ listenOnce() }, delayMs)
    }
}
