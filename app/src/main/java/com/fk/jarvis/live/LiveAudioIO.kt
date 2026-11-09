package com.fk.jarvis.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Gemini Live için ses giriş/çıkışı.
 * - Mikrofon: 16 kHz mono PCM16, VOICE_COMMUNICATION kaynağı + (varsa) yankı giderici ve gürültü bastırma.
 * - Hoparlör: 24 kHz mono PCM16 akış (AudioTrack). Kesintide tampon hemen boşaltılır.
 */
class LiveAudioIO {
    @Volatile private var recording = false
    private var recThread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    private var playThread: Thread? = null

    /** Mikrofonu açar; her ~40 ms'lik parça [onChunk] ile verilir (arka plan iş parçacığında). */
    @SuppressLint("MissingPermission")
    fun startMic(onChunk: (ByteArray, Int) -> Unit): Boolean {
        if (recording) return true
        val rate = GeminiLiveProtocol.INPUT_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } else null
        recording = true
        recThread = Thread({
            val buf = ByteArray(rate / 25 * 2) // 40 ms
            try {
                rec.startRecording()
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onChunk(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release(); aec?.release(); ns?.release()
            }
        }, "jarvis-live-mic").also { it.start() }
        return true
    }

    fun stopMic() {
        recording = false
        recThread?.join(500)
        recThread = null
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val rate = GeminiLiveProtocol.OUTPUT_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate)) // ~0.5 sn
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        framesWritten = 0
        track = t
        return t
    }

    /** Modelin ses parçasını sıraya koyar; ayrı bir iş parçacığı çalar (ağ okuması bloklanmaz). */
    fun play(pcm: ByteArray) {
        if (!playing) startPlayer()
        queue.offer(pcm)
    }

    private fun startPlayer() {
        playing = true
        playThread = Thread({
            val t = synchronized(this) { ensureTrack() }
            while (playing) {
                val chunk = try { queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
                val n = try { t.write(chunk, 0, chunk.size) } catch (e: Exception) { -1 }
                if (n > 0) framesWritten += n / 2
            }
        }, "jarvis-live-play").also { it.start() }
    }

    /** Model hâlâ ses çalıyor mu (tamponda çalınmamış ses var mı). */
    fun isPlaying(): Boolean {
        if (queue.isNotEmpty()) return true
        val t = track ?: return false
        return try { framesWritten - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) > GeminiLiveProtocol.OUTPUT_RATE / 20 } catch (e: Exception) { false }
    }

    /** Kullanıcı araya girdi: çalınmamış sesi hemen at. */
    fun flushPlayback() {
        queue.clear()
        val t = track ?: return
        try { t.pause(); t.flush(); t.play() } catch (_: Exception) {}
        framesWritten = 0
    }

    fun release() {
        stopMic()
        playing = false
        queue.clear()
        playThread?.join(500)
        playThread = null
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
    }
}
