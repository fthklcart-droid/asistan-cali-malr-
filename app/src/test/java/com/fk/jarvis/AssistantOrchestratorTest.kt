package com.fk.jarvis

import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.core.AssistantOrchestrator
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.CommandExecutor
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.HistoryStore
import com.fk.jarvis.core.MicStartResult
import com.fk.jarvis.core.RouteResult
import com.fk.jarvis.core.SpeechInput
import com.fk.jarvis.core.SpeechOutput
import com.fk.jarvis.llm.ChatTurn
import com.fk.jarvis.llm.LLMProvider
import com.fk.jarvis.llm.LlmException
import com.fk.jarvis.llm.NoLLMProvider
import com.fk.jarvis.voice.SttConfig
import com.fk.jarvis.voice.SttListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orkestratörün uçtan uca akışı (Android bağımlılıkları sahte nesnelerle). */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantOrchestratorTest {

    private class FakeInput(var result: MicStartResult = MicStartResult.Started) : SpeechInput {
        var listener: SttListener? = null
        var starts = 0
        override fun start(config: SttConfig, listener: SttListener): MicStartResult { starts++; this.listener = listener; return result }
        override fun stop() {}
        override fun cancel() {}
    }
    private class FakeOutput : SpeechOutput {
        val spoken = mutableListOf<String>()
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun stop() {}
    }
    private class FakeHistory : HistoryStore {
        val list = mutableListOf<ChatTurn>()
        override fun add(fromUser: Boolean, text: String) { list += ChatTurn(fromUser, text) }
        override fun lastUserText() = list.lastOrNull { it.fromUser }?.text
        override fun lastAssistantText() = list.lastOrNull { !it.fromUser }?.text
        override fun recentTurns(n: Int) = list.takeLast(n)
    }
    private class FakeExecutor(val result: (CommandProposal) -> RouteResult) : CommandExecutor {
        val executed = mutableListOf<CommandProposal>()
        val chosen = mutableListOf<AppInfo?>()
        override fun execute(p: CommandProposal, chosenApp: AppInfo?): RouteResult { executed += p; chosen += chosenApp; return result(p) }
    }
    private class FakeLlm(val answer: () -> CommandProposal) : LLMProvider {
        override val id = "fake"; override val isConfigured = true
        override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>) = answer()
    }

    private fun build(
        scope: TestScope,
        exec: FakeExecutor = FakeExecutor { RouteResult.Done(true, "Tamam.") },
        llm: LLMProvider = NoLLMProvider,
        input: FakeInput = FakeInput(),
        out: FakeOutput = FakeOutput(),
    ) = AssistantOrchestrator(
        scope = scope, input = input, output = out, history = FakeHistory(), executor = exec,
        llm = { llm }, listenAfterQuestion = { false },
    )

    @Test fun voiceToLocalAction() = runTest(StandardTestDispatcher()) {
        val input = FakeInput(); val out = FakeOutput()
        val exec = FakeExecutor { RouteResult.Done(true, "YouTube açıldı.") }
        val o = build(this, exec, input = input, out = out)
        o.startListening()
        assertEquals(AssistantState.LISTENING, o.ui.value.state)
        input.listener!!.onPartial("youtube")
        assertEquals("youtube", o.ui.value.partialTranscript)
        input.listener!!.onFinal("YouTube'u aç")
        assertEquals(CommandIntent.OPEN_APP, exec.executed.single().intent)
        assertEquals("YouTube açıldı.", out.spoken.last())
        assertEquals(AssistantState.IDLE, o.ui.value.state)
    }

    @Test fun sensitiveActionWaitsForConfirmation() = runTest(StandardTestDispatcher()) {
        val exec = FakeExecutor { RouteResult.Done(true, "ok") }
        val o = build(this, exec)
        o.submitText("şunu yaz merhaba")
        assertTrue(exec.executed.isEmpty())
        assertEquals(AssistantState.CONFIRMING, o.ui.value.state)
        assertNotNull(o.ui.value.confirmQuestion)
        o.confirm(false)
        assertTrue("iptal edilen işlem çalışmamalı", exec.executed.isEmpty())

        o.submitText("şunu yaz merhaba")
        o.submitText("evet")
        assertEquals(1, exec.executed.size)
        assertNull(o.ui.value.confirmQuestion)
    }

    @Test fun llmProposalIsValidatedBeforeExecution() = runTest(StandardTestDispatcher()) {
        val exec = FakeExecutor { RouteResult.Done(true, "ok") }
        val llm = FakeLlm { CommandProposal(CommandIntent.OPEN_URL, arguments = mapOf("url" to "javascript:alert(1)"), source = CommandProposal.Source.LLM) }
        val out = FakeOutput()
        val o = build(this, exec, llm, out = out)
        o.submitText("şu siteyi aç lütfen bir şey")
        assertEquals(AssistantState.PROCESSING, o.ui.value.state)
        advanceUntilIdle()
        assertTrue(exec.executed.isEmpty())
        assertTrue(out.spoken.last().contains("güvenli"))
    }

    @Test fun llmErrorDoesNotCrash() = runTest(StandardTestDispatcher()) {
        val llm = object : LLMProvider {
            override val id = "x"; override val isConfigured = true
            override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal =
                throw LlmException(LlmException.Kind.QUOTA, "429")
        }
        val out = FakeOutput()
        val o = build(this, llm = llm, out = out)
        o.submitText("bana bir şiir oku")
        advanceUntilIdle()
        assertTrue(out.spoken.last().contains("kota"))
        assertEquals(AssistantState.IDLE, o.ui.value.state)
    }

    @Test fun noProviderStillAnswersLocally() = runTest(StandardTestDispatcher()) {
        val out = FakeOutput()
        val o = build(this, out = out)
        o.submitText("bana kısa bir özet ver")
        assertTrue(out.spoken.last().contains("Gemini"))
        o.submitText("saat kaç")
        assertEquals(AssistantState.IDLE, o.ui.value.state)
    }

    @Test fun micPermissionDenied() = runTest(StandardTestDispatcher()) {
        val o = build(this, input = FakeInput(MicStartResult.NoPermission))
        o.startListening()
        assertTrue(o.ui.value.micDenied)
        assertEquals(AssistantState.ERROR, o.ui.value.state)
    }

    @Test fun ambiguousAppAsksUserToChoose() = runTest(StandardTestDispatcher()) {
        val apps = listOf(AppInfo("Notes", "a.notes"), AppInfo("Notes Pro", "b.notes"))
        val exec = FakeExecutor { p -> if (it_chosen(p)) RouteResult.Done(true, "açıldı") else RouteResult.NeedsAppChoice(apps, p) }
        val o = build(this, exec)
        o.submitText("notes aç")
        assertEquals(apps, o.ui.value.appChoices)
        o.submitText("ikinci")
        assertEquals("b.notes", exec.chosen.last()?.packageName)
        assertTrue(o.ui.value.appChoices.isEmpty())
    }

    private var chosenFlag = false
    private fun it_chosen(@Suppress("UNUSED_PARAMETER") p: CommandProposal): Boolean { val r = chosenFlag; chosenFlag = true; return r }

    @Test fun failedActionIsNotReportedAsSuccess() = runTest(StandardTestDispatcher()) {
        val out = FakeOutput()
        val o = build(this, FakeExecutor { RouteResult.Done(false, "\"photoshop\" adlı bir uygulamayı bu cihazda bulamadım.") }, out = out)
        o.submitText("Photoshop'u aç")
        assertTrue(out.spoken.last().contains("bulamadım"))
    }
}
