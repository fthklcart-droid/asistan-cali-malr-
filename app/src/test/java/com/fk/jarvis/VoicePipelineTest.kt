package com.fk.jarvis

import com.fk.jarvis.agent.AgentRunner
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.IntentClassifier
import com.fk.jarvis.core.SafetyDecision
import com.fk.jarvis.core.SafetyPolicy
import com.fk.jarvis.voice.Endpointer
import com.fk.jarvis.voice.Endpointer.Event
import com.fk.jarvis.voice.WavEncoder
import com.fk.jarvis.voice.WavEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePipelineTest {
    /** Kareleri sırayla verir, NONE dışındaki olayları (kare numarasıyla) toplar. */
    private fun run(e: Endpointer, levels: List<Float>): List<Pair<Int, Event>> {
        val out = mutableListOf<Pair<Int, Event>>()
        for ((i, l) in levels.withIndex()) { val ev = e.offer(l); if (ev != Event.NONE) { out += i to ev; if (ev != Event.SPEECH_START) break } }
        return out
    }
    private fun frames(ms: Int, db: Float) = List(ms / 20) { db }

    @Test fun detectsSpeechThenEndsAfterSilence() {
        val ev = run(Endpointer(silenceMs = 1000), frames(400, 32f) + frames(1200, 66f) + frames(1500, 33f))
        assertEquals(listOf(Event.SPEECH_START, Event.END_OF_SPEECH), ev.map { it.second })
        assertTrue("başlangıç ~400 ms", ev[0].first in 20..26)
        assertTrue("bitiş konuşmadan ~1 sn sonra", ev[1].first in 127..132)
    }

    @Test fun speakingImmediatelyAfterTapIsNotMissed() {
        // Kullanıcı düğmeye basar basmaz konuşuyor: gürültü tabanı konuşma düzeyine kilitlenmemeli.
        val e = Endpointer(silenceMs = 800)
        val ev = run(e, frames(1500, 68f) + frames(1000, 34f))
        assertEquals(listOf(Event.SPEECH_START, Event.END_OF_SPEECH), ev.map { it.second })
        assertTrue(e.speechStartMs <= 40)
    }

    @Test fun shortPausesBetweenWordsDoNotEndTheUtterance() {
        val levels = frames(300, 35f) + frames(600, 64f) + frames(500, 36f) + frames(700, 63f) + frames(1600, 35f)
        val ev = run(Endpointer(silenceMs = 1500), levels)
        assertEquals(listOf(Event.SPEECH_START, Event.END_OF_SPEECH), ev.map { it.second })
        assertTrue(ev[1].first > (300 + 600 + 500 + 700) / 20)
    }

    @Test fun silenceAndNoiseGiveNoSpeechAndLongSpeechIsCut() {
        assertEquals(listOf(Event.NO_SPEECH), run(Endpointer(noSpeechMs = 3000), frames(4000, 36f)).map { it.second })
        // Sabit gürültü (ör. vantilatör, 50 dB) konuşma sayılmaz.
        assertEquals(listOf(Event.NO_SPEECH), run(Endpointer(noSpeechMs = 3000), frames(4000, 50f)).map { it.second })
        assertEquals(listOf(Event.SPEECH_START, Event.MAX_LENGTH), run(Endpointer(maxMs = 5000), frames(200, 34f) + frames(8000, 66f)).map { it.second })
    }

    @Test fun wavEncoderProducesReadableWav() {
        val pcm = ByteArray(32_000) { i -> if (i % 2 == 1) ((i / 2) % 40).toByte() else 0 }   // 1 sn, 16 kHz
        val wav = WavEncoder.pcm16Mono(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
        val env = WavEnvelope.compute(wav)!!
        assertTrue("süre ~1000 ms: ${env.durationMs}", env.durationMs in 950..1050)
    }

    @Test fun noteCommandDoesNotSwallowOtherSentences() {
        val c = IntentClassifier()
        assertEquals(CommandIntent.NOTE_ADD, c.classify("Not: süt almayı unutma")!!.intent)
        assertEquals("yarın müşteriyi ara", c.classify("not al yarın müşteriyi ara")!!.arg("text"))
        assertNotEquals(CommandIntent.NOTE_ADD, c.classify("not defterini aç")?.intent)
        assertEquals(CommandIntent.OPEN_APP, c.classify("not defterini aç")!!.intent)
    }

    @Test fun calendarAddWithoutDayIsRejectedInsteadOfGuessingToday() {
        val d = SafetyPolicy.evaluate(CommandProposal(CommandIntent.CALENDAR_ADD, arguments = mapOf("title" to "Diş hekimi", "topic" to "", "url" to "")))
        assertTrue(d is SafetyDecision.Reject)
        val ok = SafetyPolicy.evaluate(CommandProposal(CommandIntent.CALENDAR_ADD, arguments = mapOf("title" to "Diş hekimi", "date" to "yarın", "time" to "15:00")))
        assertTrue(ok is SafetyDecision.Confirm)
    }

    @Test fun planItemsLoseTheirOwnNumbering() {
        val a = AgentRunner.parse("""{"plan":["1. Takvime bak","2) Görevleri oku","Plan öner"],"tool":"calendar_list","args":{}}""")!!
        assertEquals(listOf("Takvime bak", "Görevleri oku", "Plan öner"), a.plan)
    }
}
