package com.fk.jarvis.llm

/**
 * Sağlayıcıları bir arada tutar ve ayarlardaki seçime göre etkin olanı verir.
 * Yeni bir sağlayıcı eklemek için [LLMProvider] uygulayıp [providers] listesine eklemek yeterlidir.
 */
class AIProviderManager(
    private val selectedId: () -> String,
    val providers: List<LLMProvider>,
) {
    fun current(): LLMProvider = providers.firstOrNull { it.id == selectedId() } ?: NoLLMProvider

    fun byId(id: String): LLMProvider? = providers.firstOrNull { it.id == id }

    companion object {
        /** Ayarlar ekranında gösterilen sağlayıcı kimlikleri ve adları. */
        val choices = listOf(
            "gemini" to "Gemini",
            "openai" to "OpenAI",
            "claude" to "Claude",
            "local" to "Yerel model",
            "none" to "Yalnızca cihaz içi",
        )

        fun defaultModel(id: String): String = when (id) {
            "gemini" -> GeminiAIService.DEFAULT_MODEL
            "openai" -> OpenAICompatibleService.DEFAULT_OPENAI_MODEL
            "claude" -> ClaudeAIService.DEFAULT_MODEL
            "local" -> "llama3.1"
            else -> ""
        }

        fun keyName(id: String) = "key_$id"
    }
}
