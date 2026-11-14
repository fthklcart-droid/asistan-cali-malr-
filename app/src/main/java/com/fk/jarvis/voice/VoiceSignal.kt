package com.fk.jarvis.voice

import com.fk.jarvis.core.TextNormalizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TTS'in ürettiği WAV dosyasından dudak senkronu için ses zarfı çıkarır.
 * Sonuç: her [frameMs] milisaniye için 0..1 arası ağız açıklığı / parlaklık değeri.
 */
object WavEnvelope {
    data class Envelope(val frameMs: Int, val values: FloatArray, val durationMs: Int) {
        fun at(positionMs: Int): Float {
            if (values.isEmpty()) return 0f
            val i = (positionMs / frameMs).coerceIn(0, values.size - 1)
            return values[i]
        }
    }

    /** Yalnızca PCM 16 bit WAV desteklenir; aksi hâlde null döner (çağıran normal TTS'e düşer). */
    fun compute(wav: ByteArray, frameMs: Int = 20): Envelope? {
        if (wav.size < 44) return null
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= wav.size) {
                format = bb.getShort(body).toInt()
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                dataStart = body
                // Bazı motorlar akış hâlinde yazdığı için uzunluk alanı 0 / hatalı olabilir.
                dataLen = if (len <= 0 || body + len > wav.size) wav.size - body else len
                break
            }
            if (len < 0) return null
            pos = body + len + (len and 1)
        }
        if (format != 1 || bits != 16 || channels < 1 || rate <= 0 || dataStart < 0) return null
        val samplesPerFrame = max(1, rate * frameMs / 1000) * channels
        val totalSamples = dataLen / 2
        val frames = (totalSamples + samplesPerFrame - 1) / samplesPerFrame
        val raw = FloatArray(frames)
        var peak = 1f
        for (f in 0 until frames) {
            var sum = 0.0
            var n = 0
            val s0 = f * samplesPerFrame
            for (s in s0 until minOf(totalSamples, s0 + samplesPerFrame)) {
                val v = bb.getShort(dataStart + s * 2).toFloat()
                sum += v * v; n++
            }
            val rms = if (n > 0) sqrt(sum / n).toFloat() else 0f
            raw[f] = rms
            if (rms > peak) peak = rms
        }
        // Normalize + hafif yumuşatma (ani titremeleri azaltır).
        val out = FloatArray(frames)
        var prev = 0f
        for (i in raw.indices) {
            val v = (raw[i] / (peak * 0.7f)).coerceIn(0f, 1f)
            prev = if (v > prev) v * 0.7f + prev * 0.3f else v * 0.35f + prev * 0.65f
            out[i] = prev
        }
        val durationMs = (totalSamples.toLong() * 1000 / (rate.toLong() * channels)).toInt()
        return Envelope(frameMs, out, durationMs)
    }
}

/**
 * Basit enerji tabanlı konuşma algılayıcı (barge-in için).
 * İlk kareler ortam gürültüsünü öğrenir; gürültünün [marginDb] üstünde, art arda [minSpeechFrames]
 * kare gelirse konuşma kabul edilir.
 */
class VadGate(
    private val calibrationFrames: Int = 15,
    private val marginDb: Float = 14f,
    private val minSpeechFrames: Int = 8,
    private val absoluteFloorDb: Float = 35f,
) {
    private var seen = 0
    private var noiseDb = 0f
    private var run = 0

    /** Bir karenin dBFS-benzeri seviyesini (0..~90) verir; konuşma algılanırsa true döner. */
    fun offer(levelDb: Float): Boolean {
        if (seen < calibrationFrames) {
            noiseDb = if (seen == 0) levelDb else noiseDb * 0.8f + levelDb * 0.2f
            seen++
            return false
        }
        val threshold = max(noiseDb + marginDb, absoluteFloorDb)
        if (levelDb >= threshold) run++ else {
            run = 0
            noiseDb = noiseDb * 0.95f + levelDb * 0.05f // gürültü tabanını yavaşça izle
        }
        return run >= minSpeechFrames
    }

    companion object {
        fun levelDb(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) { val v = samples[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / count)
            return if (rms < 1.0) 0f else (20 * log10(rms)).toFloat()
        }
    }
}

/**
 * Kendi kaydedicimiz (bulut tanıyıcı) için uç nokta belirleyici: konuşmanın başladığını, bittiğini (sessizlik),
 * hiç başlamadığını ya da çok uzadığını kare kare (varsayılan 20 ms) verilen ses düzeyinden çıkarır.
 * Gürültü tabanı en düşük düzeyi izler; böylece kullanıcı düğmeye basar basmaz konuşsa da başlangıç kaçmaz.
 */
class Endpointer(
    private val frameMs: Int = 20,
    private val silenceMs: Int = 1500,
    private val noSpeechMs: Int = 7000,
    private val maxMs: Int = 25_000,
    private val startFrames: Int = 5,
    private val marginDb: Float = 11f,
    private val absoluteFloorDb: Float = 40f,
    private val noiseCapDb: Float = 52f,
) {
    enum class Event { NONE, SPEECH_START, END_OF_SPEECH, NO_SPEECH, MAX_LENGTH }

    var heardSpeech = false
        private set
    /** Konuşmanın başladığı an (ms, kaydın başından). */
    var speechStartMs = 0
        private set
    var elapsedMs = 0
        private set
    private var noiseDb = -1f
    private var run = 0
    private var quietMs = 0

    fun offer(levelDb: Float): Event {
        elapsedMs += frameMs
        noiseDb = if (noiseDb < 0) min(levelDb, noiseCapDb) else noiseDb
        val voiced = levelDb >= max(noiseDb + marginDb, absoluteFloorDb)
        if (!heardSpeech) {
            // Taban: düşük düzeye hemen iner, yukarı çok yavaş çıkar (saniyede ~2,5 dB).
            noiseDb = min(noiseCapDb, if (levelDb < noiseDb) levelDb else noiseDb + 0.05f)
            run = if (voiced) run + 1 else 0
            if (run >= startFrames) {
                heardSpeech = true; quietMs = 0
                speechStartMs = (elapsedMs - run * frameMs).coerceAtLeast(0)
                return Event.SPEECH_START
            }
            return if (elapsedMs >= noSpeechMs) Event.NO_SPEECH else Event.NONE
        }
        if (voiced) quietMs = 0 else {
            quietMs += frameMs
            noiseDb = min(noiseCapDb, noiseDb * 0.95f + levelDb * 0.05f)
        }
        return when {
            quietMs >= silenceMs -> Event.END_OF_SPEECH
            elapsedMs >= maxMs -> Event.MAX_LENGTH
            else -> Event.NONE
        }
    }
}

/** Ham PCM16 mono sesi WAV dosyası baytlarına çevirir. */
object WavEncoder {
    fun pcm16Mono(pcm: ByteArray, length: Int = pcm.size, sampleRate: Int = 16_000): ByteArray {
        val n = length.coerceIn(0, pcm.size)
        val b = java.nio.ByteBuffer.allocate(44 + n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + n).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(n).put(pcm, 0, n)
        return b.array()
    }
}

/** Konuşma metninde uyandırma sözcüğünü arar ve ardından gelen komutu ayırır. */
object WakeWordDetector {
    // Türkçe tanıyıcının "JARVIS" için üretebildiği yazımlar.
    private val variants = listOf("jarvis", "carvis", "cervis", "çarvis", "jarviz", "carviz", "ceyvis", "jervis", "javis", "jarbis", "garvis", "harvis")

    data class Hit(val command: String)

    /** Uyandırma sözcüğü bulunursa sonrasındaki metni (boş olabilir) döndürür. */
    fun detect(text: String, extra: List<String> = emptyList()): Hit? {
        val t = text.lowercase(TextNormalizer.TR).replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
        val words = t.split(" ")
        val names = variants + extra.map { it.lowercase(TextNormalizer.TR) }
        for ((i, w) in words.withIndex()) {
            val base = w.substringBefore('\'').replace('ı', 'i')
            if (names.any { base == it || (base.startsWith(it) && base.length <= it.length + 2) }) {
                return Hit(words.drop(i + 1).joinToString(" ").trim())
            }
        }
        // "hey jarvis" gibi iki sözcüğe bölünmüş tanımalar: "car vis", "jar vis"
        Regex("\\b(car|jar|cer|çar) ?vis\\b").find(t)?.let { m -> return Hit(t.substring(m.range.last + 1).trim()) }
        return null
    }
}
