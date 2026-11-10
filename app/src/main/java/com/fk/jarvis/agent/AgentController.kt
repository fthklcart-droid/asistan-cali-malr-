package com.fk.jarvis.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AJAN bölmesinin durumu ve tek seferde tek görev çalıştıran denetleyici.
 * Onay gereken adımda görev durur ve kullanıcı ONAYLA / REDDET diyene kadar bekler; yanıt gelmezse hiçbir şey yapılmaz.
 */
class AgentController(
    private val scope: CoroutineScope,
    private val runner: AgentRunner,
    /** Görev sesle başlatıldıysa sonuç ve onay soruları seslendirilir. */
    private val speak: (String) -> Unit,
) {
    data class Ui(
        val running: Boolean = false,
        val goal: String = "",
        val steps: List<AgentStep> = emptyList(),
        val confirmQuestion: String? = null,
        val answer: String? = null,
        val error: String? = null,
        val runId: Int = 0,
        val fromVoice: Boolean = false,
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var job: Job? = null
    private var pending: CompletableDeferred<Boolean>? = null

    /** Görevi başlatır. Başka bir görev çalışıyorsa false. */
    fun start(goal: String, fromVoice: Boolean = false): Boolean {
        val g = goal.trim()
        if (g.isEmpty() || job?.isActive == true) return false
        val id = _ui.value.runId + 1
        _ui.value = Ui(running = true, goal = g, runId = id, fromVoice = fromVoice, startedAt = System.currentTimeMillis())
        job = scope.launch {
            val result = try {
                runner.run(g, { q -> askConfirm(q) }) { step -> onStep(step) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(running = false, confirmQuestion = null, error = "Görev durduruldu.", finishedAt = System.currentTimeMillis()) }
                throw e
            } catch (e: Exception) {
                AgentResult.Failed("Ajan çalışırken beklenmeyen bir hata oluştu.")
            }
            when (result) {
                is AgentResult.Finished -> {
                    _ui.update { it.copy(running = false, answer = result.answer, confirmQuestion = null, finishedAt = System.currentTimeMillis()) }
                    if (fromVoice) speak(result.answer)
                }
                is AgentResult.Failed -> {
                    _ui.update { it.copy(running = false, error = result.reason, confirmQuestion = null, finishedAt = System.currentTimeMillis()) }
                    if (fromVoice) speak(result.reason)
                }
            }
        }
        return true
    }

    private fun onStep(step: AgentStep) = _ui.update { u ->
        val list = u.steps.toMutableList()
        val i = list.indexOfFirst { it.index == step.index }
        if (i >= 0) list[i] = step else list += step
        u.copy(steps = list)
    }

    private suspend fun askConfirm(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        _ui.update { it.copy(confirmQuestion = question) }
        if (_ui.value.fromVoice) speak("$question Onay için AJAN bölmesindeki düğmeleri kullan.")
        return try { d.await() } finally {
            pending = null
            _ui.update { it.copy(confirmQuestion = null) }
        }
    }

    fun answer(accepted: Boolean) { pending?.complete(accepted) }

    fun stop() {
        pending?.complete(false)
        job?.cancel()
    }

    fun clear() { if (job?.isActive != true) _ui.update { Ui(runId = it.runId) } }

    val isRunning: Boolean get() = job?.isActive == true
}
