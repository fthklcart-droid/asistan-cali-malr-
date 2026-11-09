package com.fk.jarvis.llm

import com.fk.jarvis.core.CommandProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Google AI Studio (Gemini API) sağlayıcısı.
 * - Anahtar APK'ya gömülmez; kullanıcının girdiği anahtar çalışma anında [apiKeyProvider] ile okunur.
 * - Anahtar yalnızca istek başlığında (x-goog-api-key) gönderilir, loglanmaz.
 * - Zaman aşımı, bağlantı hatası, kota (429) ve 5xx için sınırlı yeniden deneme yapar.
 */
class GeminiProvider(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val assistantName: () -> String = { "JARVIS" },
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 1000,
    client: OkHttpClient? = null,
) : LLMProvider {

    override val id = "gemini"
    override val isConfigured: Boolean get() = !apiKeyProvider().isNullOrBlank()

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "missing api key")
        val body = buildRequestBody(userText, history, context)
        val model = modelProvider().trim().ifEmpty { DEFAULT_MODEL }
        val url = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"

        var attempt = 0
        while (true) {
            try {
                val text = withContext(Dispatchers.IO) { execute(url, key, body) }
                return parseResponse(text)
            } catch (e: LlmException) {
                val retryable = e.kind == LlmException.Kind.QUOTA || e.kind == LlmException.Kind.SERVER ||
                    e.kind == LlmException.Kind.TIMEOUT || e.kind == LlmException.Kind.NETWORK
                if (!retryable || attempt >= maxRetries) throw e
                delay(retryBaseDelayMs * (1L shl attempt))
                attempt++
            }
        }
    }

    private fun execute(url: String, key: String, body: String): String {
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return text
                    resp.code == 429 -> throw LlmException(LlmException.Kind.QUOTA, "HTTP 429")
                    resp.code == 401 || resp.code == 403 -> throw LlmException(LlmException.Kind.AUTH, "HTTP ${resp.code}")
                    resp.code == 400 && text.contains("API_KEY_INVALID") -> throw LlmException(LlmException.Kind.AUTH, "invalid key")
                    resp.code >= 500 -> throw LlmException(LlmException.Kind.SERVER, "HTTP ${resp.code}")
                    else -> throw LlmException(LlmException.Kind.BAD_RESPONSE, "HTTP ${resp.code}")
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw LlmException(LlmException.Kind.TIMEOUT, "timeout", e)
        } catch (e: IOException) {
            throw LlmException(LlmException.Kind.NETWORK, "network", e)
        }
    }

    /** İstek gövdesi. Yalnızca son [MAX_HISTORY] tur ve kısa bağlam gönderilir (veri minimizasyonu). */
    fun buildRequestBody(userText: String, history: List<ChatTurn>, context: Map<String, String>): String {
        val contents = JSONArray()
        for (turn in history.takeLast(MAX_HISTORY)) {
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text.take(1000))))
            )
        }
        val ctxLine = if (context.isEmpty()) "" else
            "[Bağlam: " + context.entries.joinToString("; ") { "${it.key}=${it.value}" } + "]\n"
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", ctxLine + userText.take(2000))))
        )
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AssistantPrompt.systemInstruction(assistantName())))))
            .put("contents", contents)
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject(AssistantPrompt.responseSchemaJson()))
                    .put("maxOutputTokens", 1024)
            )
            .toString()
    }

    fun parseResponse(raw: String): CommandProposal {
        val root = try { JSONObject(raw) } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e)
        }
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
        return try {
            CommandProposal.fromJson(sb.toString())
        } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "schema", e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val MAX_HISTORY = 6
    }
}
