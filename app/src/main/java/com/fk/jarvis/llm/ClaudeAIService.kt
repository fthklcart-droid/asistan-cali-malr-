package com.fk.jarvis.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fk.jarvis.core.CommandProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Claude (Anthropic) sağlayıcısı — resmi anthropic-java SDK'sı ile.
 * - Sunucu tarafı yedek model (server-side fallback) açıktır: güvenlik sınıflandırıcısı bir isteği
 *   reddederse API uygun bir modelle yeniden dener.
 * - [research]: Claude'un web_search sunucu aracıyla güncel bilgi.
 * - [baseUrlProvider] ile anahtarı sunucuda tutan bir proxy kullanılabilir.
 */
class ClaudeAIService(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val baseUrlProvider: () -> String? = { null },
    private val assistantName: () -> String = { "JARVIS" },
) : LLMProvider {

    override val id = "claude"
    override val displayName = "Anthropic Claude"
    override val supportsResearch = true

    private val customBase: String? get() = baseUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }

    override val isConfigured: Boolean
        get() = !apiKeyProvider().isNullOrBlank() || customBase != null

    private var cached: Pair<String, AnthropicClient>? = null

    private fun client(): AnthropicClient {
        val key = apiKeyProvider()?.trim().orEmpty()
        val base = customBase
        if (key.isEmpty() && base == null) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "missing key")
        if (base != null && !EndpointPolicy.validate(base, allowPrivateHttp = true)) throw LlmException(LlmException.Kind.INSECURE_URL, "bad url")
        val cacheKey = "$key|$base"
        cached?.let { if (it.first == cacheKey) return it.second }
        val b = AnthropicOkHttpClient.builder()
            .apiKey(key.ifEmpty { "proxy" }) // proxy modunda anahtarı proxy ekler
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)
        if (base != null) b.baseUrl(base)
        val c = b.build()
        cached = cacheKey to c
        return c
    }

    private fun model() = modelProvider().trim().ifEmpty { DEFAULT_MODEL }

    private fun baseParams(system: String): MessageCreateParams.Builder =
        MessageCreateParams.builder()
            .model(model())
            .maxTokens(4096L)
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .fallbacksDefault()

    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal {
        val c = client()
        val b = baseParams(AssistantPrompt.systemInstruction(assistantName()) + "\nYanıtın yalnızca JSON nesnesi olmalı.")
        for (t in normalizeHistory(history.takeLast(GeminiAIService.MAX_HISTORY))) {
            if (t.fromUser) b.addUserMessage(t.text.take(1000)) else b.addAssistantMessage(t.text.take(1000))
        }
        b.addUserMessage(contextLine(context) + userText.take(2000))
        val msg = call { c.beta().messages().create(b.build()) }
        val text = textOf(msg)
        return try { CommandProposal.fromJson(text) } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "schema", e)
        }
    }

    override suspend fun completeJson(system: String, user: String): String {
        val c = client()
        val b = baseParams(system + "\nYanıtın yalnızca tek bir JSON nesnesi olmalı.").addUserMessage(user.take(GeminiAIService.MAX_JSON_INPUT))
        val msg = call { c.beta().messages().create(b.build()) }
        return textOf(msg).trim().ifEmpty { throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty") }
    }

    override suspend fun research(query: String, context: Map<String, String>): String {
        val c = client()
        val b = baseParams(AssistantPrompt.researchInstruction(assistantName()))
            .addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
            .addUserMessage(contextLine(context) + query.take(500))
        val msg = call { c.beta().messages().create(b.build()) }
        val text = textOf(msg).trim()
        if (text.isEmpty()) throw LlmException(LlmException.Kind.BAD_RESPONSE, "empty")
        return text
    }

    private fun textOf(msg: BetaMessage): String {
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw LlmException(LlmException.Kind.BLOCKED, "refusal")
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    private suspend fun call(block: () -> BetaMessage): BetaMessage = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: UnauthorizedException) {
            throw LlmException(LlmException.Kind.AUTH, "401", e)
        } catch (e: PermissionDeniedException) {
            throw LlmException(LlmException.Kind.AUTH, "403", e)
        } catch (e: RateLimitException) {
            throw LlmException(LlmException.Kind.QUOTA, "429", e)
        } catch (e: InternalServerException) {
            throw LlmException(LlmException.Kind.SERVER, "5xx", e)
        } catch (e: AnthropicServiceException) {
            throw LlmException(if (e.statusCode() >= 500) LlmException.Kind.SERVER else LlmException.Kind.BAD_RESPONSE, "HTTP ${e.statusCode()}", e)
        } catch (e: AnthropicIoException) {
            throw LlmException(LlmException.Kind.NETWORK, "io", e)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Claude, kullanıcı ile başlayan ve rolleri sırayla değişen mesajlar bekler: ardışık aynı roller birleştirilir. */
        fun normalizeHistory(turns: List<ChatTurn>): List<ChatTurn> {
            val out = mutableListOf<ChatTurn>()
            for (t in turns) {
                if (out.isEmpty() && !t.fromUser) continue
                val last = out.lastOrNull()
                if (last != null && last.fromUser == t.fromUser) out[out.size - 1] = ChatTurn(t.fromUser, last.text + "\n" + t.text)
                else out += t
            }
            // Son mesaj kullanıcıysa yeni kullanıcı mesajı ardından gelecek; bu durumda sonuncuyu at (sıra bozulmasın).
            if (out.lastOrNull()?.fromUser == true) out.removeAt(out.size - 1)
            return out
        }
    }
}
