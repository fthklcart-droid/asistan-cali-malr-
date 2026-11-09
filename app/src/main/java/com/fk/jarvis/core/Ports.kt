package com.fk.jarvis.core

import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.llm.ChatTurn
import com.fk.jarvis.voice.SttConfig
import com.fk.jarvis.voice.SttListener

/*
 * Orkestratörün dış dünyayla konuştuğu arayüzler. Gerçek uygulamada Android sınıfları,
 * testlerde sahte (fake) sınıflar bu arayüzleri uygular.
 */

sealed class RouteResult {
    data class Done(val success: Boolean, val message: String) : RouteResult()
    data class NeedsAppChoice(val candidates: List<AppInfo>, val proposal: CommandProposal) : RouteResult()
}

/** Kullanıcıya gösterilen sabit mesajlar. */
object Messages {
    const val MIC_DENIED_HELP =
        "Mikrofon izni verilmedi. Sesli komut için: Ayarlar > Uygulamalar > FK JARVIS > İzinler > Mikrofon > " +
            "\"Uygulamayı kullanırken izin ver\" seçeneğini aç. Bu sırada yazıyla komut gönderebilirsin."
}

sealed class MicStartResult {
    data object Started : MicStartResult()
    data object NoPermission : MicStartResult()
    data object Unavailable : MicStartResult()
}

interface SpeechInput {
    fun start(config: SttConfig, listener: SttListener): MicStartResult
    fun stop()
    fun cancel()
}

interface SpeechOutput {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
}

interface HistoryStore {
    fun add(fromUser: Boolean, text: String)
    fun lastUserText(): String?
    fun lastAssistantText(): String?
    fun recentTurns(n: Int): List<ChatTurn>
}

interface CommandExecutor {
    fun execute(p: CommandProposal, chosenApp: AppInfo? = null): RouteResult
}
