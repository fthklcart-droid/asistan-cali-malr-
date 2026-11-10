package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.IntentClassifier
import com.fk.jarvis.core.SafetyDecision
import com.fk.jarvis.core.SafetyPolicy
import com.fk.jarvis.data.MemoryStore
import com.fk.jarvis.data.TopicStore
import com.fk.jarvis.data.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryAndFeaturesTest {
    private fun tmp(): File = Files.createTempDirectory("jarvis").toFile()

    @Test fun memoryPersistsSearchesAndRefusesSecrets() {
        val dir = tmp()
        val m = MemoryStore(File(dir, "memory.json"))
        assertTrue(m.put("identity", "name", "Adı Fatih", now = 1) is MemoryStore.PutResult.Saved)
        assertTrue(m.put("preferences", "favorite_drink", "Türk kahvesini sever", now = 2) is MemoryStore.PutResult.Saved)
        assertTrue(m.put(null, null, "şifrem 1234") is MemoryStore.PutResult.Refused)
        assertTrue(m.put(null, null, "kartım 4111 1111 1111 1111") is MemoryStore.PutResult.Refused)
        // Aynı anahtar güncellenir, kalıcıdır.
        val r = m.put("kimlik", "name", "Adı Fatih Kalcı", now = 3) as MemoryStore.PutResult.Saved
        assertEquals("Adı Fatih", r.previous?.value)
        val m2 = MemoryStore(File(dir, "memory.json"))
        assertEquals(2, m2.all().size)
        assertEquals("kimlik", m2.all().first { it.key == "name" }.category)
        assertEquals("Türk kahvesini sever", m2.search("kahve").single().value)
        assertTrue(m2.promptBlock()!!.startsWith("name: Adı Fatih Kalcı"))
        // Geri alma: önceki hâl geri yüklenir.
        m2.restore("name", r.previous)
        assertEquals("Adı Fatih", m2.all().first { it.key == "name" }.value)
        assertEquals(1, m2.forget("kahve").size)
        assertEquals(1, m2.all().size)
    }

    @Test fun undoStackIsLifo() {
        val u = UndoStack()
        assertFalse(u.undoLast().first)
        val log = mutableListOf<String>()
        u.push("a") { log += "a"; true to "a geri alındı" }
        u.push("b") { log += "b"; true to "b geri alındı" }
        assertEquals("b geri alındı", u.undoLast().second)
        assertEquals(listOf("a"), u.labels())
    }

    @Test fun topicsRejectFinanceAndDetectNews() {
        val t = TopicStore(File(tmp(), "topics.json"))
        assertNull(t.add("yapay zekâ"))
        assertTrue(t.add("Bitcoin fiyatı") != null)
        assertTrue(t.add("Yapay Zekâ") != null) // tekrar
        assertTrue(t.remove("yapay"))
        assertFalse(TopicStore.isNews("YOK", ""))
        assertFalse(TopicStore.isNews("Yeni model çıktı.", "yeni model çıktı"))
        assertTrue(TopicStore.isNews("Yeni model çıktı.", ""))
    }

    @Test fun classifierRoutesNewFeatures() {
        val c = IntentClassifier()
        fun i(t: String) = c.classify(t)
        assertEquals(CommandIntent.UNDO, i("geri al")!!.intent)
        assertEquals(CommandIntent.SCREEN_ACTION, i("geri")!!.intent)
        i("bunu hatırla: annemin doğum günü 3 mayıs")!!.let {
            assertEquals(CommandIntent.REMEMBER, it.intent); assertEquals("annemin doğum günü 3 mayıs", it.arg("value"))
        }
        assertEquals(CommandIntent.RECALL, i("benim hakkımda ne biliyorsun")!!.intent)
        assertEquals(CommandIntent.DEVICE_STATUS, i("pil durumu ne")!!.intent)
        assertEquals(CommandIntent.DEVICE_STATUS, i("telefonun durumu nasıl")!!.intent)
        i("yapay zeka haberlerini takip et")!!.let { assertEquals("add", it.arg("action")); assertEquals("yapay zeka", it.arg("topic")) }
        i("uzay konusunu takip etmeyi bırak")!!.let { assertEquals("remove", it.arg("action")); assertEquals("uzay", it.arg("topic")) }
        assertEquals("list", i("takip listem")!!.arg("action"))
        i("kameraya bak ve bu bitki ne")!!.let { assertEquals(CommandIntent.CAMERA_LOOK, it.intent); assertEquals("bu bitki ne", it.arg("question")) }
        i("günaydın")!!.let { assertEquals(CommandIntent.AGENT_TASK, it.intent); assertEquals(IntentClassifier.BRIEFING_GOAL, it.arg("goal")) }
        // "takip et" tek başına sosyal medya takibiyle karışmasın: konu takibi sayılmaz.
        assertTrue(i("bu hesabı takip et")?.intent != CommandIntent.MONITOR)
    }

    @Test fun safetyForNewIntents() {
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.REMEMBER, arguments = mapOf("value" to "şifrem abc123"))) is SafetyDecision.Reject)
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.REMEMBER, arguments = mapOf("text" to "kahve sever"))) is SafetyDecision.Allow)
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.FORGET, arguments = mapOf("query" to "kahve"))) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.MONITOR, arguments = mapOf("action" to "add"))) is SafetyDecision.Reject)
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.MONITOR, arguments = mapOf("action" to "delete", "topic" to "x"))) is SafetyDecision.Reject)
    }
}
