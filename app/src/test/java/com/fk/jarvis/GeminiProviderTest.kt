package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.llm.GeminiProvider
import com.fk.jarvis.llm.LlmException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider(key: String? = "test-key") = GeminiProvider(
        apiKeyProvider = { key },
        modelProvider = { "gemini-test" },
        baseUrl = server.url("/").toString(),
        retryBaseDelayMs = 1,
    )

    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody(
        JSONObject().put("candidates", org.json.JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", json))))
        )).toString()
    )

    @Test fun parsesStructuredProposal() = runBlocking {
        server.enqueue(ok("""{"intent":"open_app","arguments":{"app_name":"YouTube"},"requires_confirmation":false,"spoken_response":"YouTube'u açıyorum."}"""))
        val p = provider().propose("youtube'u aç", emptyList(), mapOf("zaman" to "x"))
        assertEquals(CommandIntent.OPEN_APP, p.intent)
        assertEquals("YouTube", p.arg("app_name"))
        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.endsWith("/v1beta/models/gemini-test:generateContent"))
        assertFalse("anahtar URL'de olmamalı", req.path!!.contains("test-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test fun unknownIntentBecomesUnknown() = runBlocking {
        server.enqueue(ok("""{"intent":"format_disk","spoken_response":"tamam"}"""))
        assertEquals(CommandIntent.UNKNOWN, provider().propose("x", emptyList(), emptyMap()).intent)
    }

    @Test fun retriesOnQuotaThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(ok("""{"intent":"chat","spoken_response":"Merhaba"}"""))
        assertEquals("Merhaba", provider().propose("selam", emptyList(), emptyMap()).spokenResponse)
        assertEquals(2, server.requestCount)
    }

    @Test fun authErrorNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        expect(LlmException.Kind.AUTH) { provider().propose("x", emptyList(), emptyMap()) }
        assertEquals(1, server.requestCount)
    }

    @Test fun badJsonIsReported() = runBlocking {
        server.enqueue(ok("bu json değil"))
        expect(LlmException.Kind.BAD_RESPONSE) { provider().propose("x", emptyList(), emptyMap()) }
    }

    @Test fun blockedPrompt() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        expect(LlmException.Kind.BLOCKED) { provider().propose("x", emptyList(), emptyMap()) }
    }

    @Test fun missingKeyDoesNotCallNetwork() = runBlocking {
        expect(LlmException.Kind.NOT_CONFIGURED) { provider(key = null).propose("x", emptyList(), emptyMap()) }
        assertEquals(0, server.requestCount)
    }

    @Test fun networkFailure() = runBlocking {
        val pr = provider()
        server.shutdown()
        expect(LlmException.Kind.NETWORK) { pr.propose("x", emptyList(), emptyMap()) }
    }

    private suspend fun expect(kind: LlmException.Kind, block: suspend () -> Unit) {
        try { block(); fail("istisna bekleniyordu") } catch (e: LlmException) { assertEquals(kind, e.kind) }
    }
}
