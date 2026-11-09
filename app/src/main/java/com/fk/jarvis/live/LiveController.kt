package com.fk.jarvis.live

import android.os.Handler
import android.os.Looper
import com.fk.jarvis.core.AssistantCore
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.llm.EndpointPolicy
import com.fk.jarvis.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Gemini Live oturumu: mikrofon sesi WebSocket ile Gemini'ye akar, modelin sesi anında çalınır.
 * - Araya girme: Live API sunucu tarafında konuşmayı algılar ve "interrupted" gönderir; çalınmamış ses hemen atılır.
 *   Ayarlarda "araya girme" kapalıysa model konuşurken mikrofon gönderilmez (yankı ile kendi kendini kesmesin diye).
 * - Telefon işlemleri modelin "phone_action" aracıyla gelir, [AssistantCore.liveExecute] ile güvenlik politikasından geçer.
 * Tüm çekirdek çağrıları ana iş parçacığında yapılır.
 */
class LiveController(
    private val core: AssistantCore,
    private val permissions: PermissionManager,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val voice: () -> String,
    private val baseUrl: () -> String?,
    private val assistantName: () -> String,
    private val bargeIn: () -> Boolean,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val audio = LiveAudioIO()
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var sessionId = 0

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Oturumu başlatır. Başlatılamazsa kullanıcıya gösterilecek hata metnini döndürür. */
    fun start(): String? {
        if (_active.value) return null
        if (!permissions.hasMicrophone()) return "Gemini Live için mikrofon izni gerekli."
        val key = apiKey()
        val base = baseUrl()
        if (key.isNullOrBlank() && base.isNullOrBlank()) return "Gemini Live için SUNUCU sekmesinden Gemini API anahtarını kaydet."
        val url = GeminiLiveProtocol.endpoint(base, key)
        val host = try { java.net.URI(url).host } catch (e: Exception) { null }
        if (host == null || !(url.startsWith("wss://") || (url.startsWith("ws://") && EndpointPolicy.isPrivateHost(host)))) {
            return "Live sunucu adresi güvenli değil (wss gerekli)."
        }
        val id = ++sessionId
        ready = false
        _active.value = true
        core.liveStarted()
        val setup = GeminiLiveProtocol.setup(model(), voice(), assistantName())
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (id == sessionId) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (id == sessionId) handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { if (id == sessionId) finish(if (code == 1000) null else closeMessage(code, reason)) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (id == sessionId) finish(failureMessage(t, response)) }
            }
        })
        main.postDelayed(ticker, 150)
        return null
    }

    /** Oturumu kullanıcı isteğiyle kapatır. */
    fun stop() = finish(null)

    /** Yazılı komutu canlı oturuma gönderir. */
    fun sendText(text: String) {
        if (!ready || text.isBlank()) return
        core.liveUserTyped(text)
        ws?.send(GeminiLiveProtocol.text(text))
    }

    private fun finish(error: String?) {
        if (!_active.value) return
        sessionId++
        ready = false
        main.removeCallbacks(ticker)
        ws?.close(1000, "bitti")
        ws = null
        audio.release()
        _active.value = false
        core.liveEnded(error)
    }

    private fun handle(raw: String) {
        for (e in GeminiLiveProtocol.parse(raw)) {
            when (e) {
                GeminiLiveProtocol.Event.SetupComplete -> main.post { onReady() }
                is GeminiLiveProtocol.Event.Audio -> {
                    audio.play(e.pcm)
                    val lv = GeminiLiveProtocol.level(e.pcm)
                    main.post { core.liveState(AssistantState.SPEAKING); core.liveLevel(lv) }
                }
                is GeminiLiveProtocol.Event.InputText -> main.post { core.liveUserText(e.text) }
                is GeminiLiveProtocol.Event.OutputText -> main.post { core.liveAssistantText(e.text) }
                GeminiLiveProtocol.Event.Interrupted -> {
                    audio.flushPlayback()
                    main.post { core.liveLevel(0f); core.liveState(AssistantState.LISTENING) }
                }
                GeminiLiveProtocol.Event.TurnComplete -> main.post { core.liveTurnComplete() }
                is GeminiLiveProtocol.Event.ToolCall -> main.post { onToolCall(e) }
                is GeminiLiveProtocol.Event.GoAway -> Unit // sunucu oturumu yakında kapatacak; kapanınca kullanıcı yeniden başlatır
                is GeminiLiveProtocol.Event.ServerError -> main.post { finish("Gemini Live hatası: ${e.message}") }
            }
        }
    }

    private fun onReady() {
        ready = true
        core.liveState(AssistantState.LISTENING)
        val ok = audio.startMic { buf, n ->
            if (!ready) return@startMic
            val modelSpeaking = audio.isPlaying()
            if (modelSpeaking && !bargeIn()) return@startMic
            ws?.send(GeminiLiveProtocol.audio(buf, n))
            if (!modelSpeaking) {
                val lv = GeminiLiveProtocol.level(buf, n)
                main.post { core.liveLevel(lv) }
            }
        }
        if (!ok) finish("Mikrofon açılamadı.")
    }

    private fun onToolCall(e: GeminiLiveProtocol.Event.ToolCall) {
        val socket = ws ?: return
        if (e.name != GeminiLiveProtocol.TOOL_NAME) {
            socket.send(GeminiLiveProtocol.toolResponse(e.id, e.name, "BAŞARISIZ: Bilinmeyen araç."))
            return
        }
        val p = GeminiLiveProtocol.toProposal(e.args)
        if (p.intent == CommandIntent.END_SESSION || p.intent == CommandIntent.STOP) {
            socket.send(GeminiLiveProtocol.toolResponse(e.id, e.name, "BAŞARILI: Oturum kapatılıyor. Kısaca vedalaş."))
            main.postDelayed({ if (_active.value) stop() }, 3500)
            return
        }
        val result = core.liveExecute(p) { followUp ->
            // Kullanıcı ekranda onayladı/seçti/izin verdi: sonucu modele bildir, model sesli olarak aktarır.
            ws?.send(GeminiLiveProtocol.text("[Sistem bildirimi, kullanıcı söylemedi] Önceki işlemin sonucu: $followUp"))
        }
        socket.send(GeminiLiveProtocol.toolResponse(e.id, e.name, result))
    }

    /** Model sesi bittiğinde durumu DİNLİYOR'a döndürür ve seviyeyi sıfırlar. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!_active.value) return
            if (ready && !audio.isPlaying() && core.ui.value.state == AssistantState.SPEAKING) {
                core.liveLevel(0f)
                core.liveState(AssistantState.LISTENING)
            }
            main.postDelayed(this, 150)
        }
    }

    private fun closeMessage(code: Int, reason: String): String = when {
        reason.contains("API key", true) || reason.contains("API_KEY", true) -> "Gemini API anahtarı geçersiz."
        reason.contains("model", true) && (reason.contains("not found", true) || reason.contains("not supported", true)) ->
            "Live modeli bulunamadı ya da desteklenmiyor: ${model()}. SUNUCU sekmesinden model adını kontrol et."
        reason.contains("quota", true) || code == 1011 && reason.contains("exceed", true) -> "Gemini kullanım kotası doldu."
        else -> "Gemini Live bağlantısı kapandı ($code). ${reason.take(160)}"
    }

    private fun failureMessage(t: Throwable, response: Response?): String = when (response?.code) {
        401, 403 -> "Gemini Live bağlantısı reddedildi: API anahtarını kontrol et."
        400, 404 -> "Gemini Live isteği reddedildi (HTTP ${response.code}): model adı (${model()}) ya da anahtar hatalı olabilir."
        429 -> "Gemini kullanım kotası doldu."
        null -> "Gemini Live'a bağlanılamadı: internet bağlantısını kontrol et."
        else -> "Gemini Live bağlantı hatası (HTTP ${response.code})."
    }
}
