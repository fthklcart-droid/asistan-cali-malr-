package com.fkartmedia.jarvis

/** Model fiyatları (USD / 1M token) ve tahmini maliyet. Masaüstündeki app/usage.py ile aynı tablo. */
object Pricing {
    private val prices = mapOf(
        "claude-opus-5-5" to doubleArrayOf(4.0, 20.0),
        "claude-sonnet-5-5" to doubleArrayOf(2.0, 10.0),
        "claude-haiku-5-5" to doubleArrayOf(0.10, 0.50),
    )
    const val WEB_SEARCH_USD = 0.01

    fun cost(model: String, inputTokens: Long, outputTokens: Long, cacheRead: Long = 0, webSearches: Int = 0): Double? {
        val p = prices[model] ?: prices.entries.firstOrNull { model.startsWith(it.key) }?.value ?: return null
        val cacheReadPrice = p[0] * 0.05
        return (inputTokens * p[0] + outputTokens * p[1] + cacheRead * cacheReadPrice) / 1_000_000.0 +
            webSearches * WEB_SEARCH_USD
    }

    val models = listOf(
        "claude-opus-5-5" to "Opus 5.5 (en akıllı)",
        "claude-sonnet-5-5" to "Sonnet 5.5 (dengeli)",
        "claude-haiku-5-5" to "Haiku 5.5 (en hızlı/ucuz)",
    )
}
