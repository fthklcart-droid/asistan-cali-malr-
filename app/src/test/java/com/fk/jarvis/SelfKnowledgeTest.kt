package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.IntentClassifier
import com.fk.jarvis.core.SelfKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfKnowledgeTest {
    private val bare = SelfKnowledge.State(
        "JARVIS", providerName = null, research = false, vision = false, liveReady = false, mic = false, notifications = false,
        calendarRead = false, calendarWrite = false, screenControlBuild = true, screenControlOn = false, ttsReady = true,
        memoryCount = 0, openTasks = 0, routines = 0,
    )

    @Test fun neverClaimsWhatIsNotAvailable() {
        val can = SelfKnowledge.can(bare).joinToString(" ")
        assertFalse(can.contains("araştırma")); assertFalse(can.contains("takvimini")); assertFalse(can.contains("kamerayla")); assertFalse(can.contains("dokunmak"))
        val cannot = SelfKnowledge.cannot(bare).joinToString(" ")
        assertTrue(cannot.contains("mikrofon izni")); assertTrue(cannot.contains("SUNUCU")); assertTrue(cannot.contains("erişilebilirlik hizmeti kapalı"))
    }

    @Test fun reflectsLiveState() {
        val full = bare.copy(providerName = "Google Gemini", research = true, vision = true, liveReady = true, mic = true, notifications = true,
            calendarRead = true, calendarWrite = true, screenControlOn = true, memoryCount = 4, openTasks = 3, routines = 2)
        val text = SelfKnowledge.describe(full)
        assertTrue(text.contains("Google Gemini ile sohbet")); assertTrue(text.contains("onayınla etkinlik eklemek"))
        assertTrue(text.contains("3 açık görev, 2 rutin")); assertTrue(text.contains("4 bilgi hatırlıyorum")); assertTrue(text.contains("canlı mod"))
        assertFalse(text.contains("mikrofon izni olmadığı"))
        assertTrue(SelfKnowledge.cannot(bare.copy(screenControlBuild = false)).any { it.contains("basit sürümde") })
        // Her durumda söylenen kalıcı sınırlar
        assertTrue(text.contains("ödeme yapamam"))
    }

    @Test fun voiceQuestion() {
        val c = IntentClassifier()
        assertEquals(CommandIntent.CAPABILITIES, c.classify("Neler yapabilirsin?")!!.intent)
        assertEquals(CommandIntent.CAPABILITIES, c.classify("sınırların neler")!!.intent)
    }
}
