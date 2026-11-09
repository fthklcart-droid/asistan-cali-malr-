package com.fk.jarvis.llm

import com.fk.jarvis.core.CommandProposal
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI API ve OpenAI uyumlu yerel model sunucuları (Ollama, LM Studio, llama.cpp server, vLLM...).
 *
 * - [Mode.OPENAI]: https://api.openai.com/v1 (ya da https proxy). Yapılandırılmış çıktı için json_schema kullanılır;
 *   internet araştırması Responses API'nin web_search aracıyla yapılır.
 * - [Mode.LOCAL]: kullanıcının girdiği yerel sunucu (ör. http://192.168.1.20:11434/v1). http yalnızca özel ağ
 *   adreslerinde kabul edilir. Uyum için JSON, sistem talimatıyla istenir; internet araştırması yoktur.
 */
class OpenAICompatibleService(
    private val mode: Mode,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val baseUrlProvider: () -> String?,
    private val assistantName: () -> String = { "JARVIS" },
    private val httpSupport: HttpSupport = HttpSupport(),
) : LLMProvider {

    enum class Mode { OPENAI, LOCAL }

    override val id = if (mode == Mode.OPENAI) "openai" else "local"
    override val displayName = if (mode == Mode.OPENAI) "OpenAI" else "Yerel model (OpenAI uyumlu)"
    override val supportsResearch = mode == Mode.OPENAI

    private val customBase: String? get() = baseUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }

    override val isConfigured: Boolean
        get() = when (mode) {
            Mode.OPENAI -> !apiKeyProvider().isNullOrBlank() || customBase != null
            Mode.LOCAL -> customBase != null && modelProvider().isNotBlank()
        }

    private fun base(): String {
        val b = customBase ?: if (mode == Mode.OPENAI) DEFAULT_OPENAI_BASE else
            throw LlmException(LlmException.Kind.NOT_CONFIGURED, "local url missing")
        if (!EndpointPolicy.validate(b, allowPrivateHttp = true)) throw LlmException(LlmException.Kind.INSECURE_URL, "bad url")
        return b.trimEnd('/')
    }

    private fun headers(): Map<String, String> {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) {
            if (mode == Mode.OPENAI && customBase == null) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "missing key")
            return emptyMap()
        }
        return mapOf("Authorization" to "Bearer $key")
    }

    private fun model(): String = modelProvider().trim().ifEmpty {
        if (mode == Mode.OPENAI) DEFAULT_OPENAI_MODEL else throw LlmException(LlmException.Kind.NOT_CONFIGURED, "model missing")
    }

    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal {
        val headers = headers()
        val raw = httpSupport.postJson("${base()}/chat/completions", headers, buildRequestBody(userText, history, context))
        return parseChatResponse(raw)
    }

    fun buildRequestBody(userText: String, history: List<ChatTurn>, context: Map<String, String>): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", AssistantPrompt.systemInstruction(assistantName())))
        for (t in history.takeLast(GeminiAIService.MAX_HISTORY)) {
            messages.put(JSONObject().put("role", if (t.fromUser) "user" else "assistant").put("content", t.text.take(1000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", contextLine(context) + userText.take(2000)))
        val body = JSONObject().put("model", model()).put("messages", messages)
        if (mode == Mode.OPENAI) {
            body.put(
                "response_format", JSONObject().put("type", "json_schema").put(
                    "json_schema", JSONObject().put("name", "assistant_command").put("schema", JSONObject(AssistantPrompt.jsonSchemaJson()))
                )
            )
        }
        return body.toString()
    }

    fun parseChatResponse(raw: String): CommandProposal {
        val root = try { JSONObject(raw) } catch (e: Exception) { throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e) }
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "no choices")
        if (msg.optString("refusal").let { it.isNotBlank() && it != "null" }) throw LlmException(LlmException.Kind.BLOCKED, "refusal")
        val content = msg.optString("content", "")
        return try { CommandProposal.fromJson(content) } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "schema", e)
        }
    }

    override suspend fun research(query: String, context: Map<String, String>): String {
        if (mode != Mode.OPENAI) throw LlmException(LlmException.Kind.UNSUPPORTED, "local")
        val body = JSONObject()
            .put("model", model())
            .put("instructions", AssistantPrompt.researchInstruction(assistantName()))
            .put("input", contextLine(context) + query.take(500))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .toString()
        return parseResponsesText(httpSupport.postJson("${base()}/responses", headers(), body))
    }

    /** Responses API çıktısındaki output_text parçalarını birleştirir. */
    fun parseResponsesText(raw: String): String {
        val root = try { JSONObject(raw) } catch (e: Exception) { throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e) }
        val sb = StringBuilder()
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                when (c.optString("type")) {
                    "output_text" -> sb.append(c.optString("text"))
                    "refusal" -> throw LlmException(LlmException.Kind.BLOCKED, "refusal")
                }
            }
        }
        if (sb.isBlank()) throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty")
        return sb.toString().trim()
    }

    companion object {
        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-5-mini"
    }
}
