package com.fk.jarvis.core

import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.apps.AppMatch
import com.fk.jarvis.apps.AppMatcher
import com.fk.jarvis.llm.LLMProvider
import com.fk.jarvis.llm.LlmException
import com.fk.jarvis.voice.SttConfig
import com.fk.jarvis.voice.SttError
import com.fk.jarvis.voice.SttListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Asistanın beyni: istek akışını ve sesli oturumu yönetir.
 * dinle -> metne çevir -> yerel sınıflandır / yapay zekâ -> güvenlik denetimi -> (onay) -> yürüt -> seslendir.
 *
 * Sesli oturum (kesintisiz sohbet): kullanıcı sesle başlattığında [conversationActive] olur; her yanıttan sonra
 * otomatik olarak yeniden dinlenir. "Görüşürüz", "dur", iki kez üst üste sessizlik ya da DUR düğmesi oturumu bitirir.
 * Tüm çağrılar ana iş parçacığında yapılır.
 */
class AssistantCore(
    private val scope: CoroutineScope,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val history: HistoryStore,
    private val executor: CommandExecutor,
    private val classifier: IntentClassifier = IntentClassifier(),
    private val llm: () -> LLMProvider,
    private val sttConfig: () -> SttConfig = { SttConfig() },
    private val listenAfterQuestion: () -> Boolean = { true },
    private val conversationMode: () -> Boolean = { false },
    private val bargeInEnabled: () -> Boolean = { false },
    private val bargeIn: BargeInMonitor = NoBargeIn,
) {
    private val _ui = MutableStateFlow(AssistantUi())
    val ui: StateFlow<AssistantUi> = _ui.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Avatar için 0..1 ses/konuşma seviyesi (dinlerken mikrofon, konuşurken TTS ses zarfı). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _conversationActive = MutableStateFlow(false)
    val conversationActive: StateFlow<Boolean> = _conversationActive.asStateFlow()

    /** Mikrofon kullanılmadan hemen önce çağrılır (uyandırma sözcüğü döngüsünü durdurmak için). */
    var onMicRequested: (() -> Unit)? = null

    private var pendingConfirm: CommandProposal? = null
    private var pendingChoice: CommandProposal? = null
    private var pendingPermission: CommandProposal? = null
    private var llmJob: Job? = null
    private var silentTurns = 0
    /** Her seslendirmeye bir sıra numarası verilir; eski seslendirmelerin geri çağrıları yok sayılır. */
    private var speechSeq = 0

    // ------------------------------------------------------------------ giriş

    /** Kullanıcı mikrofon düğmesine bastı ya da uyandırma sözcüğü duyuldu. */
    fun startVoiceSession() {
        _conversationActive.value = conversationMode()
        silentTurns = 0
        startListening()
    }

    /** Uyandırma sözcüğü duyuldu; [command] sözcükten sonra söylenen kısım (boş olabilir). */
    fun onWakeWord(command: String) {
        _conversationActive.value = conversationMode()
        silentTurns = 0
        if (command.isNotBlank()) handleUtterance(command)
        else speak("Efendim?") { startListening() }
    }

    fun startListening() {
        speechSeq++
        bargeIn.stop()
        output.stop()
        llmJob?.cancel()
        onMicRequested?.invoke()
        val res = input.start(sttConfig(), object : SttListener {
            override fun onLevel(level: Float) { _level.value = level }
            override fun onPartial(text: String) = _ui.update { it.copy(partialTranscript = text) }
            override fun onFinal(text: String) {
                _level.value = 0f
                silentTurns = 0
                handleUtterance(text)
            }
            override fun onError(error: SttError) {
                _level.value = 0f
                onSttError(error)
            }
        })
        when (res) {
            MicStartResult.Started -> _ui.update {
                it.copy(state = AssistantState.LISTENING, partialTranscript = "", error = null, micDenied = false)
            }
            MicStartResult.NoPermission -> onMicDenied()
            MicStartResult.Unavailable -> showError("Bu cihazda konuşma tanıma hizmeti bulunamadı. Google uygulamasını yükleyip güncelleyebilir ya da yazıyla komut verebilirsin.")
        }
    }

    fun stopListening() = input.stop()

    fun cancelListening() {
        input.cancel()
        if (_ui.value.state == AssistantState.LISTENING) setState(AssistantState.IDLE)
    }

    /** Sesli oturumu tamamen bitirir (DUR düğmesi, bildirimdeki Durdur). */
    fun endSession() {
        _conversationActive.value = false
        input.cancel()
        stopSpeaking()
    }

    fun onMicDenied() {
        _conversationActive.value = false
        _ui.update { it.copy(micDenied = true, state = AssistantState.ERROR, error = Messages.MIC_DENIED_HELP) }
    }

    /** Elle yazılan komut (mikrofon yedeği). Yazılı komutlar sesli oturumu sürdürmez. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        _conversationActive.value = false
        speechSeq++
        bargeIn.stop()
        output.stop()
        if (_ui.value.state == AssistantState.LISTENING) input.cancel()
        handleUtterance(text.trim())
    }

    private fun onSttError(e: SttError) {
        when (e) {
            SttError.NO_MATCH, SttError.NO_SPEECH -> {
                if (pendingConfirm != null || pendingChoice != null) {
                    // Onay beklenirken sessizlik = onay yok.
                    _ui.update { it.copy(state = AssistantState.CONFIRMING, partialTranscript = "") }
                } else {
                    silentTurns++
                    if (_conversationActive.value && silentTurns < 2) {
                        startListening()
                    } else {
                        _conversationActive.value = false
                        _ui.update { it.copy(state = AssistantState.IDLE, partialTranscript = "") }
                    }
                }
            }
            SttError.PERMISSION -> onMicDenied()
            SttError.NETWORK -> showError("Konuşma tanıma için internet bağlantısı gerekiyor olabilir. Yazıyla komut verebilirsin.")
            SttError.BUSY -> showError("Konuşma tanıma hizmeti meşgul. Birkaç saniye sonra tekrar dene.")
            SttError.UNAVAILABLE -> showError("Konuşma tanıma hizmeti kullanılamıyor.")
            SttError.OTHER -> showError("Konuşma tanınırken bir hata oluştu.")
        }
    }

    // ------------------------------------------------------------------ işleme

    fun handleUtterance(text: String) {
        val prior = history.recentTurns(6)
        history.add(true, text)
        _ui.update { it.copy(transcript = text, partialTranscript = "", error = null) }

        if (pendingConfirm != null) {
            when (SafetyPolicy.parseYesNo(text)) {
                true -> { confirm(true); return }
                false -> { confirm(false); return }
                null -> {
                    // Yanıt evet/hayır değilse işlemi iptal edip yeni istek olarak ele al.
                    pendingConfirm = null
                    _ui.update { it.copy(confirmQuestion = null) }
                }
            }
        }
        if (pendingChoice != null) {
            val picked = pickChoice(text, _ui.value.appChoices)
            if (picked != null) { chooseApp(picked); return }
            pendingChoice = null
            _ui.update { it.copy(appChoices = emptyList(), confirmQuestion = null) }
        }

        val local = classifier.classify(text)
        if (local != null) {
            _ui.update { it.copy(lastAnswerFromCloud = false) }
            process(local)
            return
        }
        val provider = llm()
        if (!provider.isConfigured) {
            speak("Bunu cihaz içinde anlayamadım. Sohbet ve serbest sorular için Ayarlar'dan bir yapay zekâ sağlayıcısı ve API anahtarı ekleyebilirsin.")
            return
        }
        setState(AssistantState.PROCESSING)
        llmJob = scope.launch {
            try {
                val proposal = provider.propose(text, prior, contextInfo())
                _ui.update { it.copy(lastAnswerFromCloud = true) }
                process(proposal)
            } catch (e: LlmException) {
                showError(e.userMessage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Beklenmeyen bir hata oluştu.")
            }
        }
    }

    private fun contextInfo(): Map<String, String> = mapOf(
        "zaman" to SimpleDateFormat("d MMMM yyyy EEEE HH:mm", TextNormalizer.TR).format(Date()),
        "dil" to "tr-TR",
    )

    /** Öneriyi güvenlik politikasından geçirir. */
    fun process(p0: CommandProposal) {
        var p = p0
        // "Bunu paylaş": metin verilmediyse son yanıt/taslak paylaşılır (yine de onay istenir).
        if ((p.intent == CommandIntent.SHARE_TEXT || p.intent == CommandIntent.COMPOSE_MESSAGE) && p.arg("text") == null) {
            val last = lastAssistantContent() ?: run { speak("Paylaşılacak bir metin bulamadım. Önce bir metin hazırlamamı isteyebilirsin."); return }
            p = p.copy(arguments = p.arguments + ("text" to last))
        }
        when (val d = SafetyPolicy.evaluate(p)) {
            is SafetyDecision.Reject -> speak(d.reason)
            is SafetyDecision.Confirm -> askConfirmation(d.proposal, d.question)
            is SafetyDecision.Allow -> execute(d.proposal, null)
        }
    }

    private fun askConfirmation(p: CommandProposal, question: String) {
        pendingConfirm = p
        _ui.update { it.copy(confirmQuestion = question) }
        speak(question, endState = AssistantState.CONFIRMING, autoListen = false) {
            if (listenAfterQuestion() && pendingConfirm === p) startListening()
        }
    }

    /** Arayüzdeki Onayla / İptal düğmeleri ya da sesli evet/hayır. */
    fun confirm(accepted: Boolean) {
        val p = pendingConfirm ?: return
        pendingConfirm = null
        _ui.update { it.copy(confirmQuestion = null) }
        if (_ui.value.state == AssistantState.LISTENING) input.cancel()
        if (accepted) execute(p, null) else speak("Tamam, işlemi iptal ettim.")
    }

    fun chooseApp(app: AppInfo) {
        val p = pendingChoice ?: CommandProposal(CommandIntent.OPEN_APP, arguments = mapOf("app_name" to app.label))
        pendingChoice = null
        _ui.update { it.copy(appChoices = emptyList(), confirmQuestion = null) }
        if (_ui.value.state == AssistantState.LISTENING) input.cancel()
        execute(p, app)
    }

    fun cancelChoice() {
        pendingChoice = null
        _ui.update { it.copy(appChoices = emptyList(), confirmQuestion = null) }
        setState(AssistantState.IDLE)
    }

    /** Arayüz istenen izni sordu; sonuç buraya bildirilir. */
    fun onPermissionResult(granted: Boolean) {
        val p = pendingPermission ?: return
        pendingPermission = null
        _ui.update { it.copy(permissionRequest = null) }
        if (granted) execute(p, null) else speak("İzin verilmediği için bu işlemi yapamadım.")
    }

    private fun pickChoice(text: String, choices: List<AppInfo>): AppInfo? {
        if (choices.isEmpty()) return null
        val n = TextNormalizer.normalize(text)
        val ordinals = listOf("birinci", "ikinci", "üçüncü", "dördüncü", "beşinci", "altıncı")
        ordinals.forEachIndexed { i, w -> if (n.startsWith(w) && i < choices.size) return choices[i] }
        return (AppMatcher.match(n, choices) as? AppMatch.Found)?.app
    }

    private fun execute(p0: CommandProposal, chosen: AppInfo?) {
        var p = p0
        when (p.intent) {
            CommandIntent.STOP -> { endSession(); return }
            CommandIntent.END_SESSION -> {
                _conversationActive.value = false
                speak(p.spokenResponse.ifBlank { "Görüşürüz. İhtiyacın olursa buradayım." })
                return
            }
            CommandIntent.CHAT -> { speak(p.spokenResponse.ifBlank { "Anlaşıldı." }); return }
            CommandIntent.CLARIFY -> {
                speak(p.spokenResponse.ifBlank { "Biraz daha açar mısın?" }, autoListen = false) { if (listenAfterQuestion()) startListening() }
                return
            }
            CommandIntent.REPEAT_LAST -> {
                val txt = if (p.arg("who") == "user") history.recentTurns(10).dropLast(1).lastOrNull { it.fromUser }?.text?.let { "Şunu söylemiştin: $it" }
                else history.recentTurns(10).dropLast(1).lastOrNull { !it.fromUser }?.text
                speak(txt ?: "Henüz tekrar edebileceğim bir şey yok.")
                return
            }
            CommandIntent.RESEARCH -> { research(p); return }
            CommandIntent.COPY_TEXT -> {
                // "Bunu kopyala": metin verilmediyse asistanın son yanıtı kopyalanır.
                if (p.arg("text") == null) {
                    val last = lastAssistantContent() ?: run { speak("Kopyalanacak bir metin bulamadım."); return }
                    p = p.copy(arguments = p.arguments + ("text" to last))
                }
            }
            else -> Unit
        }
        setState(AssistantState.EXECUTING)
        val result = try {
            executor.execute(p, chosen)
        } catch (e: Exception) {
            RouteResult.Done(false, "İşlem sırasında bir hata oluştu.")
        }
        when (result) {
            is RouteResult.Done -> {
                // Başarı mesajı yönlendiricinin gerçek sonucundan gelir; modelin iddiası kullanılmaz.
                if (!result.success) showError(result.message)
                else if (p.intent == CommandIntent.DRAFT_TEXT) {
                    val text = p.arg("text").orEmpty()
                    _ui.update { it.copy(draft = text) }
                    speak(listOf(p.spokenResponse.ifBlank { "Metni hazırladım." }, text, result.message).filter { it.isNotBlank() }.joinToString(" "), recordText = text)
                } else speak(result.message)
            }
            is RouteResult.NeedsAppChoice -> {
                pendingChoice = result.proposal
                val names = result.candidates.mapIndexed { i, a -> "${i + 1}. ${a.label}" }.joinToString(", ")
                val q = "Birden fazla uygulama buldum: $names. Hangisini açayım?"
                _ui.update { it.copy(appChoices = result.candidates, confirmQuestion = q) }
                speak(q, endState = AssistantState.CONFIRMING, autoListen = false) { if (listenAfterQuestion() && pendingChoice != null) startListening() }
            }
            is RouteResult.NeedsPermission -> {
                pendingPermission = result.proposal
                _ui.update { it.copy(permissionRequest = result.permission) }
                speak(result.message, endState = AssistantState.CONFIRMING, autoListen = false)
            }
        }
    }

    private fun lastAssistantContent(): String? =
        _ui.value.draft?.takeIf { it.isNotBlank() } ?: history.recentTurns(10).dropLast(1).lastOrNull { !it.fromUser }?.text

    private fun research(p: CommandProposal) {
        val query = p.arg("query") ?: run {
            speak("Neyi araştırmamı istersin?", autoListen = false) { if (listenAfterQuestion()) startListening() }
            return
        }
        val provider = llm()
        if (!provider.isConfigured) {
            speak("İnternet araştırması için Ayarlar'dan Gemini, Claude ya da OpenAI sağlayıcısını ve API anahtarını eklemelisin.")
            return
        }
        if (!provider.supportsResearch) {
            speak(LlmException(LlmException.Kind.UNSUPPORTED, "").userMessage())
            return
        }
        setState(AssistantState.PROCESSING)
        llmJob = scope.launch {
            try {
                val answer = provider.research(query, contextInfo())
                _ui.update { it.copy(lastAnswerFromCloud = true) }
                speak(answer)
            } catch (e: LlmException) {
                showError(e.userMessage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Araştırma sırasında beklenmeyen bir hata oluştu.")
            }
        }
    }

    // ------------------------------------------------------------------ çıkış

    /**
     * Yanıtı seslendirir. Sesli oturum açıksa ve [autoListen] true ise bittiğinde yeniden dinlemeye geçer.
     * Ayar açıksa konuşma sırasında kullanıcı sesi algılanırsa yanıt kesilir ve dinlemeye geçilir (barge-in).
     */
    fun speak(
        text: String,
        endState: AssistantState = AssistantState.IDLE,
        autoListen: Boolean = true,
        recordText: String? = null,
        then: (() -> Unit)? = null,
    ) {
        history.add(false, recordText ?: text)
        _ui.update { it.copy(state = AssistantState.SPEAKING, response = text) }
        val seq = ++speechSeq
        if (bargeInEnabled()) {
            bargeIn.start {
                if (seq == speechSeq && _ui.value.state == AssistantState.SPEAKING) {
                    _conversationActive.value = _conversationActive.value || conversationMode()
                    startListening() // TTS'i durdurur ve dinlemeye geçer
                }
            }
        }
        output.speak(text) {
            if (seq != speechSeq) return@speak
            bargeIn.stop()
            _level.value = 0f
            if (_ui.value.state == AssistantState.SPEAKING) setState(endState)
            when {
                then != null -> then()
                autoListen && endState == AssistantState.IDLE && _conversationActive.value -> startListening()
            }
        }
    }

    private fun showError(message: String) {
        history.add(false, message)
        _ui.update { it.copy(state = AssistantState.ERROR, error = message, response = message) }
        val seq = ++speechSeq
        output.speak(message) {
            if (seq != speechSeq) return@speak
            if (_ui.value.state == AssistantState.ERROR) setState(AssistantState.IDLE)
            if (_conversationActive.value) startListening()
        }
    }

    fun stopSpeaking() {
        speechSeq++
        bargeIn.stop()
        output.stop()
        _level.value = 0f
        val waiting = pendingConfirm != null || pendingChoice != null || pendingPermission != null
        setState(if (waiting) AssistantState.CONFIRMING else AssistantState.IDLE)
    }

    fun onTtsLevel(v: Float) {
        if (_ui.value.state == AssistantState.SPEAKING) _level.value = v
    }

    fun dismissError() = _ui.update { it.copy(error = null, micDenied = false, state = if (it.state == AssistantState.ERROR) AssistantState.IDLE else it.state) }

    fun clearDraft() = _ui.update { it.copy(draft = null) }

    private fun setState(s: AssistantState) = _ui.update { it.copy(state = s) }
}
