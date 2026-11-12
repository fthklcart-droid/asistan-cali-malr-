package com.fk.jarvis

import com.fk.jarvis.agent.AgentAction
import com.fk.jarvis.agent.AgentProfiles
import com.fk.jarvis.agent.AgentRunner
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.IntentClassifier
import com.fk.jarvis.core.SafetyDecision
import com.fk.jarvis.core.SafetyPolicy
import com.fk.jarvis.data.NoteStore
import com.fk.jarvis.data.Routine
import com.fk.jarvis.data.RoutineStore
import com.fk.jarvis.data.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PlanningTest {
    private fun tmp(): File = Files.createTempDirectory("jarvis").toFile()
    private val today = LocalDate.of(2026, 11, 12) // Perşembe

    @Test fun tasksPersistSortFindAndComplete() {
        val dir = tmp()
        val s = TaskStore(File(dir, "tasks.json"))
        val a = s.add("Raporu gönder", due = "2026-11-13", priority = 2, now = 1)!!
        s.add("Market alışverişi", now = 2)
        s.add("Faturayı öde", due = "2026-11-10", now = 3)
        assertEquals(listOf("Faturayı öde", "Raporu gönder", "Market alışverişi"), s.open().map { it.title })
        assertEquals(listOf("Faturayı öde"), s.forDay(today).map { it.title })              // gecikmiş görev bugüne düşer
        assertEquals("Raporu gönder", s.find("rapor").single().title)
        s.setDone(a.id, true)
        val s2 = TaskStore(File(dir, "tasks.json"))
        assertEquals(2, s2.open().size)
        assertTrue(s2.all().first { it.id == a.id }.done)
        val d = TaskStore.describe(s2.open(), today)
        assertTrue(d, d.contains("gecikmiş") && d.contains("Market alışverişi"))
        assertEquals(2, TaskStore.parsePriority("acil")); assertEquals(0, TaskStore.parsePriority("düşük")); assertEquals(1, TaskStore.parsePriority(null))
    }

    @Test fun notesRefuseSecretsAndSearch() {
        val n = NoteStore(File(tmp(), "notes.json"))
        assertNotNull(n.add("Ahmet Bey ile toplantı: teklif cuma günü gidecek", now = 1))
        assertNull(n.add("banka şifrem 4821"))
        assertEquals(1, n.search("teklif").size)
        assertEquals(0, n.search("fatura").size)
    }

    @Test fun routineNextFire() {
        val thu10 = LocalDateTime.of(2026, 11, 12, 10, 0)
        val once = Routine(1, "x", Routine.Kind.ONCE, "09:00", date = "2026-11-13")
        assertEquals(LocalDateTime.of(2026, 11, 13, 9, 0), once.nextFire(thu10))
        assertNull(once.copy(date = "2026-11-12").nextFire(thu10))                              // geçmiş
        val daily = Routine(2, "x", Routine.Kind.DAILY, "08:30")
        assertEquals(LocalDateTime.of(2026, 11, 13, 8, 30), daily.nextFire(thu10))
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 30), daily.nextFire(thu10.withHour(7)))
        val weekly = Routine(3, "x", Routine.Kind.WEEKLY, "17:00", days = setOf(1, 5))          // Pzt, Cum
        assertEquals(LocalDateTime.of(2026, 11, 13, 17, 0), weekly.nextFire(thu10))
        assertEquals(LocalDateTime.of(2026, 11, 16, 17, 0), weekly.nextFire(LocalDateTime.of(2026, 11, 13, 17, 0)))
        assertNull(daily.copy(enabled = false).nextFire(thu10))
    }

    @Test fun routineStoreDueOnceAndRepeat() {
        val rs = RoutineStore(File(tmp(), "r.json"))
        val now = LocalDateTime.of(2026, 11, 12, 10, 0)
        assertNotNull(rs.add(Routine(0, "İlaç", Routine.Kind.DAILY, "10:05", action = Routine.Action.REMIND, text = "İlacını al"), now).first)
        assertNotNull(rs.add(Routine(0, "Toplantı", Routine.Kind.ONCE, "10:05", date = "2026-11-12"), now).first)
        assertNotNull(rs.add(Routine(0, "Geçmiş", Routine.Kind.ONCE, "09:00", date = "2026-11-12"), now).second)
        assertNotNull(rs.add(Routine(0, "Bozuk", Routine.Kind.DAILY, "25:99"), now).second)
        assertEquals(LocalDateTime.of(2026, 11, 12, 10, 5), rs.nextFire(now))
        assertTrue(rs.takeDue(now).isEmpty())
        val due = rs.takeDue(now.plusMinutes(6))
        assertEquals(setOf("İlaç", "Toplantı"), due.map { it.title }.toSet())
        assertTrue(rs.takeDue(now.plusMinutes(7)).isEmpty())                                     // aynı zaman iki kez çalışmaz
        assertFalse(rs.all().first { it.title == "Toplantı" }.enabled)                           // tek seferlik kapanır
        assertEquals(LocalDateTime.of(2026, 11, 13, 10, 5), rs.nextFire(now.plusMinutes(7)))
        assertEquals(listOf("İlaç"), rs.takeDue(now.plusDays(1).plusMinutes(5)).map { it.title })
    }

    @Test fun quietHoursAndRepeatParsing() {
        assertTrue(RoutineStore.inQuietHours(LocalTime.of(23, 30), "23:00", "07:00"))
        assertTrue(RoutineStore.inQuietHours(LocalTime.of(6, 59), "23:00", "07:00"))
        assertFalse(RoutineStore.inQuietHours(LocalTime.of(7, 0), "23:00", "07:00"))
        assertFalse(RoutineStore.inQuietHours(LocalTime.of(12, 0), "", ""))
        assertEquals(Routine.Kind.DAILY, RoutineStore.parseRepeat("her gün").first)
        assertEquals(setOf(1, 2, 3, 4, 5), RoutineStore.parseRepeat("hafta içi").second)
        assertEquals(setOf(1, 6), RoutineStore.parseRepeat("pazartesi ve cumartesi").second)     // "cuma"/"pazar" yanlış eşleşmez
        assertEquals(setOf(5, 7), RoutineStore.parseRepeat("cuma pazar").second)
        assertEquals(Routine.Kind.ONCE, RoutineStore.parseRepeat(null).first)
    }

    @Test fun voiceCommandsForPlanning() {
        val c = IntentClassifier()
        c.classify("görev ekle: yarın raporu gönder")!!.let {
            assertEquals(CommandIntent.TASK_ADD, it.intent); assertEquals("raporu gönder", it.arg("title")); assertEquals("yarın", it.arg("date"))
        }
        assertEquals(CommandIntent.TASK_LIST, c.classify("görevlerim neler")!!.intent)
        assertEquals("bugün", c.classify("bugünkü görevlerim")!!.arg("date"))
        c.classify("rapor görevini tamamla")!!.let { assertEquals(CommandIntent.TASK_DONE, it.intent); assertEquals("rapor", it.arg("query")) }
        c.classify("not al: müşteri cuma günü dönecek")!!.let { assertEquals(CommandIntent.NOTE_ADD, it.intent); assertEquals("müşteri cuma günü dönecek", it.arg("text")) }
        c.classify("yarın saat 9'da toplantıyı hatırlat")!!.let {
            assertEquals(CommandIntent.ROUTINE_ADD, it.intent); assertEquals("09:00", it.arg("time")); assertEquals("yarın", it.arg("date"))
            assertTrue(it.arg("text")!!, it.arg("text")!!.contains("toplantı"))
        }
        assertEquals("her gün", c.classify("her gün saat 8'de ilacımı hatırlat")!!.arg("repeat"))
        assertEquals(CommandIntent.ROUTINE_LIST, c.classify("hatırlatıcılarım")!!.intent)
        // Eski komutlar bozulmadı
        assertEquals(CommandIntent.REMEMBER, c.classify("bunu hatırla: kahveyi şekersiz içerim")!!.intent)
    }

    @Test fun safetyNormalizesPlanningIntents() {
        val t = SafetyPolicy.evaluate(CommandProposal(CommandIntent.TASK_ADD, arguments = mapOf("title" to "Rapor", "date" to "yarın", "priority" to "acil"))) as SafetyDecision.Allow
        assertEquals(LocalDate.now().plusDays(1).toString(), t.proposal.arg("date")); assertEquals("2", t.proposal.arg("priority"))
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.NOTE_ADD, arguments = mapOf("text" to "kart numaram 4111 1111 1111 1111"))) is SafetyDecision.Reject)
        val r = SafetyPolicy.evaluate(CommandProposal(CommandIntent.ROUTINE_ADD, arguments = mapOf("text" to "İlaç", "time" to "8", "repeat" to "hafta içi"))) as SafetyDecision.Allow
        assertEquals("WEEKLY", r.proposal.arg("kind")); assertEquals("1,2,3,4,5", r.proposal.arg("days")); assertEquals("08:00", r.proposal.arg("time"))
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.ROUTINE_ADD, arguments = mapOf("text" to "Haberleri araştır", "time" to "08:00", "repeat" to "her gün", "action" to "task"))) is SafetyDecision.Confirm)
        assertTrue(SafetyPolicy.evaluate(CommandProposal(CommandIntent.ROUTINE_ADD, arguments = mapOf("text" to "x"))) is SafetyDecision.Reject)
    }

    @Test fun agentProfilesAndPlanParsing() {
        assertEquals(AgentProfiles.RESEARCHER, AgentProfiles.pick("Elektrikli araç pazarını derin araştır ve rapor hazırla"))
        assertEquals(AgentProfiles.BUSINESS, AgentProfiles.pick("Müşteriye teklif e-postası taslağı yaz"))
        assertEquals(AgentProfiles.PLANNER, AgentProfiles.pick("Yarınki günümü planla"))
        assertEquals(AgentProfiles.GENERAL, AgentProfiles.pick("YouTube'u aç"))
        val a: AgentAction = AgentRunner.parse("""{"plan":["Takvime bak","Görevleri oku","Plan öner"],"tool":"calendar_list","args":{}}""")!!
        assertEquals(3, a.plan.size)
        val sys = AgentRunner.systemPrompt("JARVIS", "2026-11-12", emptyList(), AgentProfiles.RESEARCHER)
        assertTrue(sys.contains("verify_quote") && sys.contains("PROFİL: ARAŞTIRMACI"))
    }
}
