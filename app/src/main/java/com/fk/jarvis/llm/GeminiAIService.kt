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
    // Sesli asistan: yoğun/yanıtsız modelde uzun süre beklemek yerine hızla yedek modele geçilir.
    private val httpSupport: HttpSupport = HttpSupport(maxRetries = 0, callTimeoutSec = 25),
    /** Sesli yanıt bu süreden uzun sürerse model bir süre geri plana alınır. */
    private val slowMs: Long = 6000,
    /** Tüm modeller kota hatası verince ikinci tur öncesi bekleme (0: ikinci tur yok). */
    private val quotaRetryDelayMs: Long = 5000,
) : LLMProvider {

    /** Testler ve geriye dönük uyumluluk için sabit adresli kurucu. */
    constructor(
        apiKeyProvider: () -> String?,
        modelProvider: () -> String,
        baseUrl: String,
        retryBaseDelayMs: Long = 1000,
        slowMs: Long = 6000,
    ) : this(apiKeyProvider, modelProvider, { "JARVIS" }, { baseUrl }, HttpSupport(maxRetries = 0, retryBaseDelayMs = retryBaseDelayMs), slowMs, retryBaseDelayMs)

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val supportsResearch = true

    private val customBase: String? get() = baseUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }

    override val isConfigured: Boolean
        get() = !apiKeyProvider().isNullOrBlank() || customBase != null

    private fun endpoint(): String {
        val base = customBase ?: DEFAULT_BASE_URL
        if (!EndpointPolicy.validate(base, allowPrivateHttp = true)) throw LlmException(LlmException.Kind.INSECURE_URL, "bad base url")
        return "${base.trimEnd('/')}/v1beta/models/${activeModel()}:generateContent"
    }

    private fun headers(): Map<String, String> {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) {
            if (customBase == null) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "missing api key")
            return emptyMap() // proxy anahtarı kendisi ekler
        }
        return mapOf("x-goog-api-key" to key)
    }

    /**
     * Model sağlığı ve hızı:
     * - Yoğun (503), yanıtsız, kotası dolmuş ya da bulunamayan model [FALLBACK_HOLD_MS] boyunca sona atılır; yeniden
     *   başarılı olduğu an işareti kalkar (aksi hâlde hepsi bir kez hata verince sıra başa dönüp her istekte yine
     *   çalışmayan ilk model beklenirdi).
     * - Sesli yanıt isteğinde [slowMs]'ten uzun süren model [SLOW_HOLD_MS] boyunca hızlı olanların arkasına alınır.
     * - Tüm modeller kota hatası verirse (dakikalık sınır) kısa bir bekleyişten sonra bir tur daha denenir.
     */
    private val badUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val slowUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastGood: String? = null
    @Volatile private var thinkingRejected = false
    @Volatile private var currentModel: String? = null

    private fun selectedModel() = modelProvider().trim().ifEmpty { DEFAULT_MODEL }
    private fun activeModel(): String = currentModel ?: selectedModel()

    /** Denenecek modeller: sağlıklı ve hızlı olanlar (seçili model önce), sonra yavaşlar, en sonda sorunlular. */
    private fun candidates(preferred: List<String>?): List<String> {
        val now = System.currentTimeMillis()
        val all = (preferred ?: (listOf(selectedModel()) + FALLBACK_MODELS)).distinct()
        val healthy = all.filter { (badUntil[it] ?: 0L) <= now }
        val fast = healthy.filter { (slowUntil[it] ?: 0L) <= now }
        val slow = healthy.filter { it !in fast }
        val sick = all.filter { it !in healthy }.sortedBy { if (it == lastGood) Long.MIN_VALUE else (badUntil[it] ?: 0L) }
        return fast + slow + sick
    }

    /**
     * Sırayla modelleri dener; düşünme ayarı reddedilirse aynı modelde onsuz bir kez daha dener.
     * @param interactive sesli yanıt isteği: yavaş kalan model sonraki isteklerde geri plana alınır.
     */
    private suspend fun <T> withFallbacks(interactive: Boolean = false, models: List<String>? = null, block: suspend () -> T): T {
        var last: LlmException? = null
        for (round in 0..1) {
            for (m in candidates(models)) {
                currentModel = m
                val t0 = System.nanoTime()
                try {
                    val r = try { block() } catch (e: LlmException) {
                        if (e.kind == LlmException.Kind.BAD_REQUEST && !thinkingRejected && (e.detail ?: "").contains("think", true)) {
                            thinkingRejected = true; block()
                        } else throw e
                    }
                    badUntil.remove(m); lastGood = m
                    if (interactive) {
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        if (ms > slowMs) slowUntil[m] = System.currentTimeMillis() + SLOW_HOLD_MS else slowUntil.remove(m)
                    }
                    return r
                } catch (e: LlmException) {
                    last = e
                    if (e.kind !in FALLBACK_KINDS) throw e
                    badUntil[m] = System.currentTimeMillis() + FALLBACK_HOLD_MS
                } finally {
                    currentModel = null
                }
            }
            if (round == 0 && last?.kind == LlmException.Kind.QUOTA && quotaRetryDelayMs > 0) kotlinx.coroutines.delay(quotaRetryDelayMs) else break
        }
        throw last ?: LlmException(LlmException.Kind.BAD_RESPONSE, "no model")
    }

    override suspend fun transcribe(wav: ByteArray, languageTag: String): String = withFallbacks(models = TRANSCRIBE_MODELS) {
        val headers = headers()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", java.util.Base64.getEncoder().encodeToString(wav))))
            .put(JSONObject().put("text", AssistantPrompt.transcribeInstruction(languageTag)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024).put("temperature", 0).also { thinking(it) })
            .toString()
        val root = try { JSONObject(httpSupport.postJson(endpoint(), headers, body)) } catch (e: org.json.JSONException) { throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e) }
        // Boş aday (ör. sessizlik) hata değil: konuşma yok demektir.
        val text = try { extractText(root) } catch (e: LlmException) { if (e.kind == LlmException.Kind.BAD_RESPONSE) "" else throw e }
        // Sessizlik/gürültüde model bazen anlamsız kısa çıktı üretir ("00", "..."): harf içermeyen döküm konuşma sayılmaz.
        text.trim().trim('"').let { if (it.contains(AssistantPrompt.NO_SPEECH_MARK) || it.none { c -> c.isLetter() }) "" else it }
    }

    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal = withFallbacks(interactive = true) {
        val headers = headers()
        val raw = httpSupport.postJson(endpoint(), headers, buildRequestBody(userText, history, context))
        parseResponse(raw)
    }

    override suspend fun research(query: String, context: Map<String, String>): String = withFallbacks { researchOnce(query, context) }

    private suspend fun researchOnce(query: String, context: Map<String, String>): String {
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

    override suspend fun vision(question: String, jpeg: ByteArray): String = withFallbacks {
        val headers = headers()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", java.util.Base64.getEncoder().encodeToString(jpeg))))
            .put(JSONObject().put("text", question.take(500)))
        val body = JSONObject()
            .put("systemInstruction", textParts(AssistantPrompt.visionInstruction(assistantName())))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048).also { thinking(it) })
            .toString()
        val root = try { JSONObject(httpSupport.postJson(endpoint(), headers, body)) } catch (e: org.json.JSONException) { throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e) }
        extractText(root).trim().ifEmpty { throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty") }
    }

    override suspend fun completeJson(system: String, user: String): String = withFallbacks {
        val headers = headers()
        val body = JSONObject()
            .put("systemInstruction", textParts(system))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user.take(MAX_JSON_INPUT))))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 4096).also { thinking(it) })
            .toString()
        val raw = httpSupport.postJson(endpoint(), headers, body)
        val root = try { JSONObject(raw) } catch (e: Exception) { throw LlmException(LlmException.Kind.BAD_RESPONSE, "not json", e) }
        extractText(root).trim().ifEmpty { throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty") }
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
        if (!thinkingRejected && activeModel().startsWith("gemini-3")) cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
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
        /** Seçili model kullanılamaz ya da yoğunsa (503) sırayla denenen hızlı modeller. */
        val FALLBACK_MODELS = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3-flash-preview")
        const val FALLBACK_MODEL = "gemini-3.5-flash"
        const val FALLBACK_HOLD_MS = 30 * 60 * 1000L
        const val SLOW_HOLD_MS = 10 * 60 * 1000L
        /** Yazıya dökme kısa ve basit bir iştir: en hızlı modelden başlanır. */
        val TRANSCRIBE_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3-flash-preview")
        private val FALLBACK_KINDS = setOf(
            LlmException.Kind.MODEL_NOT_FOUND, LlmException.Kind.SERVER, LlmException.Kind.TIMEOUT, LlmException.Kind.QUOTA,
        )
        const val MAX_HISTORY = 6
        const val MAX_JSON_INPUT = 24_000
    }
}
