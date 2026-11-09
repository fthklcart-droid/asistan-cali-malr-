package com.fk.jarvis.llm

import com.fk.jarvis.core.CommandProposal

/** Konuşma geçmişinin API'ye gönderilen (sınırlandırılmış) hâli. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** Sağlayıcı hatalarının sınıflandırılmış hâli; kullanıcıya anlaşılır mesaj üretmek için. */
class LlmException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NOT_CONFIGURED, NETWORK, TIMEOUT, QUOTA, AUTH, SERVER, BAD_RESPONSE, BLOCKED }

    fun userMessage(): String = when (kind) {
        Kind.NOT_CONFIGURED -> "Yapay zekâ sağlayıcısı ayarlanmadı. Ayarlardan Gemini API anahtarını girebilirsin."
        Kind.NETWORK -> "İnternete bağlanamadım. Yerel komutları kullanmaya devam edebilirsin."
        Kind.TIMEOUT -> "Yapay zekâ sunucusu zamanında yanıt vermedi."
        Kind.QUOTA -> "Gemini kullanım kotası doldu. Biraz sonra tekrar dene."
        Kind.AUTH -> "Gemini API anahtarı geçersiz ya da yetkisiz görünüyor. Ayarlardan kontrol et."
        Kind.SERVER -> "Gemini sunucusunda bir sorun oluştu."
        Kind.BAD_RESPONSE -> "Yapay zekâdan anlaşılır bir yanıt alamadım."
        Kind.BLOCKED -> "Bu isteğe güvenlik filtresi nedeniyle yanıt veremiyorum."
    }
}

/**
 * Değiştirilebilir LLM sağlayıcı arayüzü. Sağlayıcı yalnızca yapılandırılmış bir
 * [CommandProposal] döndürür; dönen öneri doğrudan çalıştırılmaz.
 */
interface LLMProvider {
    val id: String
    val isConfigured: Boolean

    @Throws(LlmException::class)
    suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal
}

/** Sağlayıcı yapılandırılmamışken kullanılan, hiç ağ çağrısı yapmayan sağlayıcı. */
object NoLLMProvider : LLMProvider {
    override val id = "none"
    override val isConfigured = false
    override suspend fun propose(userText: String, history: List<ChatTurn>, context: Map<String, String>): CommandProposal =
        throw LlmException(LlmException.Kind.NOT_CONFIGURED, "no provider")
}
