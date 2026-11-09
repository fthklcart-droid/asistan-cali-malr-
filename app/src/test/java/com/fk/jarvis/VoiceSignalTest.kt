package com.fk.jarvis

import com.fk.jarvis.apps.AgendaFormatter
import com.fk.jarvis.voice.VadGate
import com.fk.jarvis.voice.WakeWordDetector
import com.fk.jarvis.voice.WavEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.sin

class VoiceSignalTest {

    /** 16 kHz mono 16-bit WAV: 200 ms sessizlik + 300 ms ton + 200 ms sessizlik. */
    private fun wav(): ByteArray {
        val rate = 16000
        val samples = ShortArray(rate * 7 / 10)
        for (i in rate / 5 until rate / 5 + rate * 3 / 10) samples[i] = (sin(2 * PI * 220 * i / rate) * 12000).toInt().toShort()
        val data = samples.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        samples.forEach { bb.putShort(it) }
        return bb.array()
    }

    @Test fun envelopeFollowsLoudness() {
        val env = WavEnvelope.compute(wav(), frameMs = 20)!!
        assertEquals(700, env.durationMs)
        assertTrue("sessizlikte ağız kapalı", env.at(100) < 0.05f)
        assertTrue("ses varken ağız açık", env.at(400) > 0.5f)
        assertTrue("sonda kapanır", env.at(690) < 0.3f)
    }

    @Test fun envelopeRejectsNonPcm() {
        assertNull(WavEnvelope.compute(ByteArray(10)))
        assertNull(WavEnvelope.compute("OggS0000000000000000000000000000000000000000000".toByteArray()))
    }

    @Test fun vadIgnoresSteadyNoiseAndDetectsSpeech() {
        val g = VadGate()
        repeat(15) { assertFalse(g.offer(40f)) }           // kalibrasyon: ortam gürültüsü
        repeat(30) { assertFalse(g.offer(42f)) }           // gürültüdeki küçük oynamalar konuşma değil
        var hit = false
        repeat(10) { hit = hit || g.offer(62f) }           // konuşma
        assertTrue(hit)
    }

    @Test fun vadNeedsSustainedSpeech() {
        val g = VadGate()
        repeat(15) { g.offer(40f) }
        repeat(5) { assertFalse(g.offer(65f)) }            // kısa tık sesi
        assertFalse(g.offer(40f))
    }

    @Test fun wakeWord() {
        assertEquals("youtube'u aç", WakeWordDetector.detect("Jarvis YouTube'u aç")!!.command)
        assertEquals("", WakeWordDetector.detect("hey jarvis")!!.command)
        assertNotNull(WakeWordDetector.detect("Carvis saat kaç"))
        assertNotNull(WakeWordDetector.detect("JARVIS"))                // "jarvıs"
        assertEquals("sesi aç", WakeWordDetector.detect("car vis sesi aç")!!.command)
        assertNull(WakeWordDetector.detect("bugün hava çok güzel"))
        assertNull(WakeWordDetector.detect("javascript öğreniyorum"))
    }

    @Test fun agendaText() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("Bugün takviminde kayıtlı bir etkinlik görünmüyor.", AgendaFormatter.describe(emptyList(), utc))
        val t = AgendaFormatter.describe(listOf(
            AgendaFormatter.Item("Toplantı", 10L * 3600_000, false),
            AgendaFormatter.Item("Doğum günü", 0, true),
        ), utc)
        assertEquals("Bugün takviminde 2 etkinlik var: 10:00 Toplantı, gün boyu Doğum günü.", t)
    }
}
