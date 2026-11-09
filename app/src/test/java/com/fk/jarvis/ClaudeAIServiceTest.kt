package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.llm.ChatTurn
import com.fk.jarvis.llm.ClaudeAIService
import com.fk.jarvis.llm.LlmException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClaudeAIServiceTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun svc() = ClaudeAIService({ "sk-ant-test" }, { "claude-opus-5-5" }, { server.url("/").toString() })

    private fun message(text: String, stop: String = "end_turn") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            JSONObject()
                .put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
                .toString()
        )

    @Test fun proposeUsesSdkWithFallbackAndParsesJson() = runBlocking {
        server.enqueue(message("""{"intent":"open_app","arguments":{"app_name":"YouTube"},"spoken_response":"Açıyorum."}"""))
        val p = svc().propose("youtube'u aç", listOf(ChatTurn(false, "Merhaba"), ChatTurn(true, "selam")), emptyMap())
        assertEquals(CommandIntent.OPEN_APP, p.intent)
        val req = server.takeRequest()
        // SDK, anahtarı sürümüne göre x-api-key ya da Authorization: Bearer başlığında gönderir.
        assertTrue(req.getHeader("x-api-key") == "sk-ant-test" || req.getHeader("Authorization") == "Bearer sk-ant-test")
        assertTrue(req.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("default", body.getString("fallbacks"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        val msgs = body.getJSONArray("messages")
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test fun refusalIsBlocked() = runBlocking {
        server.enqueue(message("", stop = "refusal"))
        try { svc().propose("x", emptyList(), emptyMap()); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.BLOCKED, e.kind) }
    }

    @Test fun authError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try { svc().propose("x", emptyList(), emptyMap()); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.AUTH, e.kind) }
    }

    @Test fun researchAddsWebSearchTool() = runBlocking {
        server.enqueue(message("Bugünün öne çıkan gelişmeleri şöyle."))
        assertEquals("Bugünün öne çıkan gelişmeleri şöyle.", svc().research("haberler", emptyMap()))
        val tools = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("tools")
        assertEquals("web_search_20260209", tools.getJSONObject(0).getString("type"))
    }

    @Test fun historyIsNormalizedForAlternation() {
        val h = ClaudeAIService.normalizeHistory(
            listOf(ChatTurn(false, "a"), ChatTurn(true, "b"), ChatTurn(true, "c"), ChatTurn(false, "d"), ChatTurn(true, "e"))
        )
        assertEquals(listOf(ChatTurn(true, "b\nc"), ChatTurn(false, "d")), h)
    }
}
