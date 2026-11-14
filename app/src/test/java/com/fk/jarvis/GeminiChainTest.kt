package com.fk.jarvis

import com.fk.jarvis.llm.AssistantPrompt
import com.fk.jarvis.llm.GeminiAIService
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
import java.util.concurrent.TimeUnit

/** Gerçek sunucu denetiminde bulunan hataların testleri: model sırası, yavaş model, kota, yazıya dökme, şema sırası. */
class GeminiChainTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider(model: String = "gemini-3.8-flash", slowMs: Long = 6000) =
        GeminiAIService({ "k" }, { model }, server.url("/").toString(), retryBaseDelayMs = 1, slowMs = slowMs)
    private fun ok(text: String) = MockResponse().setResponseCode(200).setBody(
        JSONObject().put("candidates", org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))))).toString()
    )
    private fun chat(s: String) = ok("""{"intent":"chat","spoken_response":"$s"}""")
    private fun busy() = MockResponse().setResponseCode(503).setBody("""{"error":{"code":503,"message":"high demand"}}""")
    private fun model() = Regex("models/([^:]+):").find(server.takeRequest().path!!)!!.groupValues[1]

    @Test fun afterEveryModelFailedOnceTheWorkingOneGoesFirst() = runBlocking {
        val p = provider()
        repeat(4) { server.enqueue(busy()) }                       // 1. istek: dört model de yoğun
        try { p.propose("a", emptyList(), emptyMap()); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.SERVER, e.kind) }
        repeat(4) { model() }
        server.enqueue(busy()); server.enqueue(chat("iki"))        // 2. istek: 3.8 yine yoğun, 3.5 çalışıyor
        assertEquals("iki", p.propose("b", emptyList(), emptyMap()).spokenResponse)
        assertEquals("gemini-3.8-flash", model()); assertEquals("gemini-3.5-flash", model())
        server.enqueue(chat("üç"))                                 // 3. istek: doğrudan çalışan modele
        assertEquals("üç", p.propose("c", emptyList(), emptyMap()).spokenResponse)
        assertEquals("gemini-3.5-flash", model())
        assertEquals(7, server.requestCount)
    }

    @Test fun slowModelIsMovedBehindFasterOnes() = runBlocking {
        val p = provider(model = "gemini-3.5-flash", slowMs = 80)
        server.enqueue(chat("yavaş").setBodyDelay(250, TimeUnit.MILLISECONDS))
        assertEquals("yavaş", p.propose("a", emptyList(), emptyMap()).spokenResponse)
        assertEquals("gemini-3.5-flash", model())
        server.enqueue(chat("hızlı"))
        assertEquals("hızlı", p.propose("b", emptyList(), emptyMap()).spokenResponse)
        assertEquals("gemini-3.5-flash-lite", model())             // yavaş kalan model atlandı
        // Sesli yanıt olmayan çağrılar (ajan adımı) modeli yavaş diye işaretlemez.
        val q = provider(model = "gemini-3.5-flash", slowMs = 80)
        server.enqueue(ok("""{"tool":"finish"}""").setBodyDelay(250, TimeUnit.MILLISECONDS)); server.enqueue(ok("""{"tool":"finish"}"""))
        q.completeJson("s", "u"); q.completeJson("s", "u")
        assertEquals("gemini-3.5-flash", model()); assertEquals("gemini-3.5-flash", model())
    }

    @Test fun waitsAndRetriesWhenAllModelsHitRateLimit() = runBlocking {
        val p = provider()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"quota"}}""")) }
        server.enqueue(chat("tamam"))
        assertEquals("tamam", p.propose("a", emptyList(), emptyMap()).spokenResponse)
        assertEquals(5, server.requestCount)
    }

    @Test fun transcribeSendsWavToFastModelAndHandlesSilence() = runBlocking {
        val p = provider()
        server.enqueue(ok("YouTube'u aç")); server.enqueue(ok(AssistantPrompt.NO_SPEECH_MARK)); server.enqueue(ok("\"merhaba\""))
        assertEquals("YouTube'u aç", p.transcribe(ByteArray(64) { 1 }))
        val r = server.takeRequest()
        assertTrue(r.path!!.contains("/models/gemini-3.5-flash-lite:generateContent"))
        val part = JSONObject(r.body.readUtf8()).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data")
        assertEquals("audio/wav", part.getString("mime_type")); assertTrue(part.getString("data").isNotEmpty())
        assertEquals("", p.transcribe(ByteArray(64)))
        assertEquals("merhaba", p.transcribe(ByteArray(64)))
        server.enqueue(ok("00"))
        assertEquals("", p.transcribe(ByteArray(64)))
    }

    @Test fun geminiSchemaOrdersContentFieldsBeforeQualifiers() {
        val order = JSONObject(AssistantPrompt.geminiSchemaJson()).getJSONObject("properties").getJSONObject("arguments").getJSONArray("propertyOrdering")
        val list = (0 until order.length()).map { order.getString(it) }
        // Model önce içerik alanını yazar ve geri dönemez: tarih/saat içerikten SONRA gelmeli.
        for (main in listOf("title", "text", "query")) for (q in listOf("date", "time", "repeat", "priority")) assertTrue("$main < $q", list.indexOf(main) < list.indexOf(q))
        assertEquals(JSONObject(AssistantPrompt.geminiSchemaJson()).getJSONObject("properties").getJSONObject("arguments").getJSONObject("properties").length(), list.size)
        assertFalse(AssistantPrompt.jsonSchemaJson().contains("propertyOrdering"))
    }
}
