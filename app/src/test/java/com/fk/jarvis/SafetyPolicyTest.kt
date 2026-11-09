package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.SafetyDecision
import com.fk.jarvis.core.SafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private fun p(i: CommandIntent, vararg args: Pair<String, String>, target: String? = null, confirm: Boolean = false) =
        CommandProposal(i, target, mapOf(*args), confirm, "")

    @Test fun unknownRejected() = assertTrue(SafetyPolicy.evaluate(p(CommandIntent.UNKNOWN)) is SafetyDecision.Reject)

    @Test fun messagesAlwaysNeedConfirmation() {
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.COMPOSE_MESSAGE, "text" to "selam", "app_name" to "whatsapp")) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SHARE_TEXT, "text" to "selam")) is SafetyDecision.Confirm)
    }

    @Test fun sensitiveClickNeedsConfirmation() {
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SCREEN_ACTION, "action" to "click", "text" to "Gönder")) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SCREEN_ACTION, "action" to "click", "text" to "Ödemeyi tamamla")) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SCREEN_ACTION, "action" to "click", "text" to "Ayarlar")) is SafetyDecision.Allow)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SCREEN_ACTION, "action" to "type", "text" to "x")) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SCREEN_ACTION, "action" to "swipe_unlock")) is SafetyDecision.Reject)
    }

    @Test fun modelCanOnlyAddConfirmation() {
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.OPEN_APP, "app_name" to "youtube", confirm = true)) is SafetyDecision.Confirm)
        // Model "onay gerekmez" dese bile mesaj gönderme onay ister.
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.COMPOSE_MESSAGE, "text" to "x", confirm = false)) is SafetyDecision.Confirm)
    }

    @Test fun urls() {
        assertEquals("https://example.com", SafetyPolicy.sanitizeUrl("example.com"))
        assertNull(SafetyPolicy.sanitizeUrl("javascript:alert(1)"))
        assertNull(SafetyPolicy.sanitizeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(SafetyPolicy.sanitizeUrl("file:///sdcard/x"))
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.OPEN_URL, "url" to "file:///etc")) is SafetyDecision.Reject)
    }

    @Test fun argumentValidation() {
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.DIAL, "number" to "abc")) is SafetyDecision.Reject)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.DIAL, "number" to "+90 555 123 45 67")) is SafetyDecision.Allow)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SET_TIMER, "seconds" to "0")) is SafetyDecision.Reject)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.SET_ALARM, "hour" to "25")) is SafetyDecision.Reject)
        assertTrue(SafetyPolicy.evaluate(p(CommandIntent.OPEN_SETTINGS, target = "developer_root")) is SafetyDecision.Reject)
    }

    @Test fun yesNo() {
        assertEquals(true, SafetyPolicy.parseYesNo("Evet onaylıyorum"))
        assertEquals(false, SafetyPolicy.parseYesNo("hayır vazgeç"))
        assertNull(SafetyPolicy.parseYesNo("bilmiyorum"))
    }
}
