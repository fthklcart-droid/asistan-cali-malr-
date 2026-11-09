package com.fk.jarvis.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** REST tabanlı sağlayıcılar için ortak HTTP çağrısı: zaman aşımı, hata sınıflandırma, sınırlı yeniden deneme. */
class HttpSupport(
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 1000,
    client: OkHttpClient? = null,
) {
    val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { execute(url, headers, body) }
            } catch (e: LlmException) {
                val retryable = e.kind == LlmException.Kind.QUOTA || e.kind == LlmException.Kind.SERVER ||
                    e.kind == LlmException.Kind.TIMEOUT || e.kind == LlmException.Kind.NETWORK
                if (!retryable || attempt >= maxRetries) throw e
                delay(retryBaseDelayMs * (1L shl attempt))
                attempt++
            }
        }
    }

    private fun execute(url: String, headers: Map<String, String>, body: String): String {
        val b = Request.Builder().url(url).post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        try {
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return text
                    resp.code == 429 -> throw LlmException(LlmException.Kind.QUOTA, "HTTP 429")
                    resp.code == 401 || resp.code == 403 -> throw LlmException(LlmException.Kind.AUTH, "HTTP ${resp.code}")
                    resp.code == 400 && (text.contains("API_KEY_INVALID") || text.contains("invalid_api_key")) ->
                        throw LlmException(LlmException.Kind.AUTH, "invalid key")
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
        } catch (e: IllegalArgumentException) {
            throw LlmException(LlmException.Kind.INSECURE_URL, "bad url", e)
        }
    }
}
