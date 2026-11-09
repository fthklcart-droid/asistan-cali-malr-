package com.fk.jarvis.llm

import com.fk.jarvis.core.CommandProposal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google AI Studio (Gemini API) sağlayıcısı.
 * - Anahtar APK'ya gömülmez; kullanıcının girdiği anahtar çalışma anında okunur ve yalnızca
 *   `x-goog-api-key` başlığında gönderilir (URL'ye, loglara yazılmaz).
 * - [baseUrl] değiştirilerek anahtarı sunucuda tutan bir proxy kullanılabilir; proxy modunda anahtar boş bırakılabilir.
 * - [research]: Google Search grounding aracıyla güncel bilgi.
 */
class GeminiAIService(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val assistantName: () -> String = { "JARVIS" },
    private val baseUrlProvider: () -> String? = { null },
    private val httpSupport: HttpSupport = HttpSupport(),
) : LLMProvider {

    /** Testler ve geriye dönük uyumluluk için sabit adresli kurucu. */
    constructor(
        apiKeyProvider: () -> String?,
        modelProvider: () -> String,
        baseUrl: String,
        retryBaseDelayMs: Long = 1000,
    ) : this(apiKeyProvider, modelProvider, { "JARVIS" }, { baseUrl }, HttpSupport(retryBaseDelayMs = retryBaseDelayMs))

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val supportsResearch = true

    private val customBase: String? get() = baseUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }

    override val isConfigured: Boolean
        get() = !apiKeyProvider().isNullOrBlank() || customBase != null

    private fun endpoint(): String {
        val base = customBase ?: DEFAULT_BASE_URL
        if (!EndpointPolicy.validate(base, allowPrivateHttp = true)) throw LlmException(LlmException.Kind.INSECURE_URL, "bad base url")
        val model = modelProvider().trim().ifEmpty { DEFAULT_MODEL }
        return "${base.trimEnd('/')}/v1beta/models/$model:generateContent"
    }

    private fun headers(): Map<String, String> {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) {
            if (customBase == null) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "missing api key")
            return emptyMap() // proxy anahtarı kendisi ekler
        }
        return mapOf("x-goog-api-key" to key)
    }

    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal {
        val headers = headers()
        val raw = httpSupport.postJson(endpoint(), headers, buildRequestBody(userText, history, context))
        return parseResponse(raw)
    }

    override suspend fun research(query: String, context: Map<String, String>): String {
        val headers = headers()
        val body = JSONObject()
            .put("systemInstruction", textParts(AssistantPrompt.researchInstruction(assistantName())))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", contextLine(context) + query.take(500))))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096).also { thinking(it) })
            .toString()
        val text = extractText(JSONObject(httpSupport.postJson(endpoint(), headers, body)))
        if (text.isBlank()) throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty")
        return text.trim()
    }

    /** İstek gövdesi. Yalnızca son [MAX_HISTORY] tur ve kısa bağlam gönderilir (veri minimizasyonu). */
    fun buildRequestBody(userText: String, history: List<ChatTurn>, context: Map<String, String>): String {
        val contents = JSONArray()
        for (turn in history.takeLast(MAX_HISTORY)) {
            contents.put(
                JSONObject().put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text.take(1000))))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", contextLine(context) + userText.take(2000))))
        )
        return JSONObject()
            .put("systemInstruction", textParts(AssistantPrompt.systemInstruction(assistantName())))
            .put("contents", contents)
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject(AssistantPrompt.geminiSchemaJson()))
                    .put("maxOutputTokens", 8192)
                    .also { thinking(it) }
            )
            .toString()
    }

    /** Gemini 3 ailesinde düşünme düzeyi "low": sesli asistan için hızlı yanıt. Eski modellere gönderilmez. */
    private fun thinking(cfg: JSONObject) {
        val m = modelProvider().trim().ifEmpty { DEFAULT_MODEL }
        if (m.startsWith("gemini-3")) cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
    }

    private fun textParts(t: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun extractText(root: JSONObject): String {
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw LlmException(LlmException.Kind.BLOCKED, it)
        }
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "no candidates")
        if (cand.optString("finishReason") == "SAFETY") throw LlmException(LlmException.Kind.BLOCKED, "safety")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "no parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    fun parseResponse(raw: String): CommandProposal {
        val root = try { JSONObject(raw) } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e)
        }
        val text = extractText(root)
        return try {
            CommandProposal.fromJson(text)
        } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "schema", e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val MAX_HISTORY = 6
    }
}
