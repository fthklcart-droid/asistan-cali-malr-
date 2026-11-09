package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.llm.EndpointPolicy
import com.fk.jarvis.llm.HttpSupport
import com.fk.jarvis.llm.LlmException
import com.fk.jarvis.llm.OpenAICompatibleService
import com.fk.jarvis.llm.OpenAICompatibleService.Mode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAICompatibleServiceTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun svc(mode: Mode, base: String? = server.url("/v1").toString(), key: String? = "sk-test", model: String = "m") =
        OpenAICompatibleService(mode, { key }, { model }, { base }, { "JARVIS" }, HttpSupport(retryBaseDelayMs = 1))

    private fun chat(content: String) = MockResponse().setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()
    )

    @Test fun openAiStructuredOutput() = runBlocking {
        server.enqueue(chat("""{"intent":"draft_text","arguments":{"text":"Yeni sezon geldi!"},"spoken_response":"Hazırladım."}"""))
        val p = svc(Mode.OPENAI).propose("instagram metni hazırla", emptyList(), emptyMap())
        assertEquals(CommandIntent.DRAFT_TEXT, p.intent)
        assertEquals("Yeni sezon geldi!", p.arg("text"))
        val req = server.takeRequest()
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.path!!.endsWith("/v1/chat/completions"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun localModelToleratesProseAroundJson() = runBlocking {
        server.enqueue(chat("Tabii! İşte yanıt:\n```json\n{\"intent\":\"chat\",\"spoken_response\":\"Merhaba\"}\n```"))
        val p = svc(Mode.LOCAL, key = null).propose("selam", emptyList(), emptyMap())
        assertEquals("Merhaba", p.spokenResponse)
        val req = server.takeRequest()
        assertEquals(null, req.getHeader("Authorization"))
        assertFalse(JSONObject(req.body.readUtf8()).has("response_format"))
    }

    @Test fun localRequiresUrl() {
        assertFalse(svc(Mode.LOCAL, base = null).isConfigured)
    }

    @Test fun cleartextOnlyForPrivateNetwork() = runBlocking {
        assertTrue(EndpointPolicy.validate("http://192.168.1.20:11434/v1", allowPrivateHttp = true))
        assertTrue(EndpointPolicy.validate("http://localhost:8080", allowPrivateHttp = true))
        assertFalse(EndpointPolicy.validate("http://ornek.com/v1", allowPrivateHttp = true))
        assertFalse(EndpointPolicy.validate("ftp://192.168.1.2", allowPrivateHttp = true))
        try {
            svc(Mode.LOCAL, base = "http://8.8.8.8/v1").propose("x", emptyList(), emptyMap())
            fail("güvensiz adres kabul edilmemeli")
        } catch (e: LlmException) { assertEquals(LlmException.Kind.INSECURE_URL, e.kind) }
        assertEquals(0, server.requestCount)
    }

    @Test fun localDoesNotSupportResearch() = runBlocking {
        try { svc(Mode.LOCAL).research("x", emptyMap()); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.UNSUPPORTED, e.kind) }
    }

    @Test fun openAiResearchParsesResponsesOutput() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"Özet metin."}]}]}"""))
        assertEquals("Özet metin.", svc(Mode.OPENAI).research("haberler", emptyMap()))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("type"))
    }

    @Test fun refusalIsBlocked() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":null,"refusal":"Yardımcı olamam"}}]}"""))
        try { svc(Mode.OPENAI).propose("x", emptyList(), emptyMap()); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.BLOCKED, e.kind) }
    }
}
