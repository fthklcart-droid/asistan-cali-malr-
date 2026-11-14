package com.fk.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.fk.jarvis.llm.LlmException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Bulut konuşma tanıyıcı: telefonun tanıma hizmetine HİÇ bağlı değildir.
 * Sesi kendisi kaydeder (16 kHz mono), konuşmanın başını ve sonunu [Endpointer] ile bulur ve kaydı yazıya dökülmesi için
 * yapay zekâ sağlayıcısına (Gemini) gönderir. Telefonda Google tanıyıcısı yoksa, Türkçe paketi eksikse ya da hizmet
 * hata veriyorsa konuşma yine tanınır.
 *
 * Gizlilik: bu motor kullanılırken söylediklerinin ses kaydı yazıya dökülmek üzere Gemini API'ye gönderilir;
 * kayıt telefonda saklanmaz. Mikrofon yalnızca dinleme sırasında açıktır.
 * Tüm çağrılar ana iş parçacığından yapılmalıdır; geri çağrılar ana iş parçacığında gelir.
 */
class CloudSttEngine(
    private val scope: CoroutineScope,
    /** WAV kaydını yazıya döker; konuşma yoksa boş metin. Hata: [LlmException]. */
    private val transcribe: suspend (wav: ByteArray, languageTag: String) -> String,
    private val ready: () -> Boolean,
) : SpeechToTextProvider {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session = 0
    @Volatile private var stopRequested = false
    private var thread: Thread? = null
    private var job: Job? = null

    /** Son hatanın kullanıcıya gösterilecek açıklaması. */
    var lastErrorText: String? = null
        private set

    override val isAvailable: Boolean get() = ready()

    @SuppressLint("MissingPermission")
    override fun start(config: SttConfig, listener: SttListener) {
        cancel()
        val id = ++session
        stopRequested = false
        lastErrorText = null
        begin(id, config, listener, retried = false)
    }

    @SuppressLint("MissingPermission")
    private fun begin(id: Int, config: SttConfig, listener: SttListener, retried: Boolean) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            if (min <= 0) null else AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            // Mikrofonu az önce bırakan başka bir bileşen (araya girme dinleyicisi, uyandırma döngüsü) olabilir: bir kez bekleyip dene.
            if (!retried) { main.postDelayed({ if (id == session) begin(id, config, listener, retried = true) }, 400); return }
            lastErrorText = "mikrofon açılamadı"
            listener.onError(SttError.AUDIO)
            return
        }
        thread = Thread(body@{
            val frame = ShortArray(RATE / 50)                       // 20 ms
            val bytes = ByteArray(frame.size * 2)
            val pcm = ByteArrayOutputStream(RATE * 2 * 8)
            val ep = Endpointer(frameMs = 20, silenceMs = config.silenceTimeoutMs.coerceIn(700, 4000))
            var result = Endpointer.Event.NONE
            var failed = false
            var n = 0
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) failed = true
                else main.post { if (id == session) listener.onReady() }
                while (!failed && id == session && !stopRequested && result == Endpointer.Event.NONE) {
                    val read = rec.read(frame, 0, frame.size)
                    if (read < 0) { failed = true; break }
                    if (read == 0) continue
                    for (i in 0 until read) { bytes[2 * i] = (frame[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte() }
                    pcm.write(bytes, 0, read * 2)
                    val db = VadGate.levelDb(frame, read)
                    if (++n % 2 == 0) { val lv = ((db - 38f) / 34f).coerceIn(0f, 1f); main.post { if (id == session) listener.onLevel(lv) } }
                    val ev = ep.offer(db)
                    if (ev != Endpointer.Event.NONE && ev != Endpointer.Event.SPEECH_START) result = ev
                }
            } catch (e: Exception) {
                failed = true
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
            if (id != session) return@body                           // iptal edildi
            main.post {
                if (id != session) return@post
                listener.onLevel(0f)
                when {
                    failed -> { lastErrorText = "mikrofondan ses okunamadı (başka bir uygulama kullanıyor olabilir)"; listener.onError(SttError.AUDIO) }
                    !ep.heardSpeech -> listener.onError(SttError.NO_SPEECH)
                    else -> {
                        // Baştaki sessizliği at (konuşmadan önce 300 ms bırak): daha kısa yükleme, daha doğru sonuç.
                        val all = pcm.toByteArray()
                        val from = (((ep.speechStartMs - 300).coerceAtLeast(0)) * RATE / 1000 * 2).coerceIn(0, all.size)
                        send(id, WavEncoder.pcm16Mono(all.copyOfRange(from, all.size), sampleRate = RATE), config, listener)
                    }
                }
            }
        }, "jarvis-cloud-stt").also { it.start() }
    }

    private fun send(id: Int, wav: ByteArray, config: SttConfig, listener: SttListener) {
        listener.onPartial("Yazıya dökülüyor…")
        job = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { transcribe(wav, config.languageTag) }.trim()
                if (id != session) return@launch
                if (text.isEmpty()) listener.onError(SttError.NO_MATCH) else listener.onFinal(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LlmException) {
                if (id != session) return@launch
                lastErrorText = "Gemini ile tanıma: " + e.userMessage()
                listener.onError(if (e.kind == LlmException.Kind.NETWORK || e.kind == LlmException.Kind.TIMEOUT) SttError.NETWORK else SttError.OTHER)
            } catch (e: Exception) {
                if (id != session) return@launch
                lastErrorText = "Gemini ile tanıma başarısız (${e.javaClass.simpleName})"
                listener.onError(SttError.OTHER)
            }
        }
    }

    /** Kullanıcı konuşmayı bitirdi: eldeki ses hemen yazıya dökülür. */
    override fun stop() { stopRequested = true }

    override fun cancel() {
        session++
        job?.cancel(); job = null
        thread?.join(400); thread = null
    }

    override fun destroy() = cancel()

    companion object { const val RATE = 16_000 }
}
