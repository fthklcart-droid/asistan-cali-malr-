package com.fk.jarvis.llm

import com.fk.jarvis.core.CommandProposal

/** Konuşma geçmişinin API'ye gönderilen (sınırlandırılmış) hâli. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** Sağlayıcı hatalarının sınıflandırılmış hâli; kullanıcıya anlaşılır mesaj üretmek için. */
class LlmException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    /** Sunucunun döndürdüğü hata metni (kullanıcıya gösterilir; anahtar içermez). */
    val detail: String? = null,
) : Exception(message, cause) {
    enum class Kind { NOT_CONFIGURED, NETWORK, TIMEOUT, QUOTA, AUTH, SERVER, BAD_RESPONSE, BLOCKED, UNSUPPORTED, INSECURE_URL, MODEL_NOT_FOUND, BAD_REQUEST, NO_VISION }

    fun userMessage(): String = base() + (detail?.takeIf { it.isNotBlank() }?.let { " Sunucu yanıtı: ${it.take(220)}" } ?: "")

    private fun base(): String = when (kind) {
        Kind.NOT_CONFIGURED -> "Yapay zekâ sağlayıcısı ayarlanmadı. Ayarlardan bir sağlayıcı seçip API anahtarını girebilirsin."
        Kind.NETWORK -> "İnternete bağlanamadım. Yerel komutları kullanmaya devam edebilirsin."
        Kind.TIMEOUT -> "Yapay zekâ sunucusu zamanında yanıt vermedi."
        Kind.QUOTA -> "Yapay zekâ kullanım kotası doldu. Biraz sonra tekrar dene."
        Kind.AUTH -> "API anahtarı geçersiz ya da yetkisiz görünüyor. Ayarlardan kontrol et."
        Kind.SERVER -> "Yapay zekâ sunucusunda bir sorun oluştu."
        Kind.BAD_RESPONSE -> "Yapay zekâdan anlaşılır bir yanıt alamadım."
        Kind.BLOCKED -> "Bu isteğe güvenlik filtresi nedeniyle yanıt veremiyorum."
        Kind.UNSUPPORTED -> "Seçili yapay zekâ sağlayıcısı internet araştırmasını desteklemiyor. Gemini, Claude veya OpenAI seçebilirsin."
        Kind.MODEL_NOT_FOUND -> "Seçili model bu anahtarla kullanılamıyor ya da adı hatalı. SUNUCU bölmesinden model adını değiştir."
        Kind.BAD_REQUEST -> "Yapay zekâ sunucusu isteği reddetti."
        Kind.NO_VISION -> "Seçili yapay zekâ sağlayıcısıyla görüntü analizi yapamıyorum. SUNUCU bölmesinden Gemini ya da OpenAI seçebilirsin."
        Kind.INSECURE_URL -> "Sunucu adresi güvenli değil. Bulut sağlayıcılar için https kullanılmalı; http yalnızca yerel ağdaki model sunucusu için kabul edilir."
    }
}

/**
 * Değiştirilebilir LLM sağlayıcı arayüzü.
 * - [propose]: kullanıcı isteğini yapılandırılmış bir [CommandProposal]'a çevirir (doğrudan çalıştırılmaz).
 * - [research]: internette arama yaparak kısa, sesli okunabilir bir özet üretir (destekleyen sağlayıcılar).
 */
interface LLMProvider {
    val id: String
    val displayName: String
    val isConfigured: Boolean
    val supportsResearch: Boolean get() = false

    @Throws(LlmException::class)
    suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal

    @Throws(LlmException::class)
    suspend fun research(query: String, context: Map<String, String>): String =
        throw LlmException(LlmException.Kind.UNSUPPORTED, "research not supported")

    /**
     * Serbest yapılandırılmış görev (ajan adımı, sayfa özeti): sistem talimatı + tek kullanıcı mesajı,
     * yanıt olarak JSON nesnesi içeren metin döner.
     */
    /** Görüntü analizi: JPEG fotoğraf + soru -> kısa, sesli okunacak Türkçe yanıt. */
    @Throws(LlmException::class)
    suspend fun vision(question: String, jpeg: ByteArray): String =
        throw LlmException(LlmException.Kind.NO_VISION, "vision not supported")

    @Throws(LlmException::class)
    suspend fun completeJson(system: String, user: String): String =
        throw LlmException(LlmException.Kind.NOT_CONFIGURED, "completeJson not supported")
}

/** Sağlayıcı yapılandırılmamışken kullanılan, hiç ağ çağrısı yapmayan sağlayıcı. */
object NoLLMProvider : LLMProvider {
    override val id = "none"
    override val displayName = "Yalnızca cihaz içi"
    override val isConfigured = false
    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal =
        throw LlmException(LlmException.Kind.NOT_CONFIGURED, "no provider")
}

internal fun contextLine(context: Map<String, String>): String =
    if (context.isEmpty()) "" else "[Bağlam: " + context.entries.joinToString("; ") { "${it.key}=${it.value}" } + "]\n"

/** Sağlayıcı sunucu adresi güvenlik kontrolü. */
object EndpointPolicy {
    /** Bulut sağlayıcılar (ve proxy'leri) yalnızca https; yerel model sunucusu http olabilir ama yalnızca özel ağ/loopback adresinde. */
    fun validate(url: String, allowPrivateHttp: Boolean): Boolean {
        val u = try { java.net.URI(url.trim()) } catch (e: Exception) { return false }
        val host = u.host ?: return false
        return when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> allowPrivateHttp && isPrivateHost(host)
            else -> false
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
    }
}
