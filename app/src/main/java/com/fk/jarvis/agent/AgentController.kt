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
    /** Rapor geçmişinin saklandığı dosya (null: saklanmaz). */
    private val historyFile: java.io.File? = null,
    /** Görev bittiğinde (başarılı/başarısız) çağrılır — uygulama arka plandaysa bildirim göstermek için. */
    private val onFinished: (goal: String, text: String, ok: Boolean) -> Unit = { _, _, _ -> },
) {
    /** Tamamlanan görevin kaydı (RAPORLAR). */
    data class Report(val goal: String, val text: String, val ok: Boolean, val at: Long, val profile: String, val steps: Int)

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<Report>> = _history.asStateFlow()

    private fun loadHistory(): List<Report> = try {
        val f = historyFile
        if (f == null || !f.exists()) emptyList() else {
            val a = org.json.JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Report(it.optString("goal"), it.optString("text"), it.optBoolean("ok"), it.optLong("at"), it.optString("profile"), it.optInt("steps")) }
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun addReport(r: Report) {
        val list = (listOf(r) + _history.value).take(30)
        _history.value = list
        val f = historyFile ?: return
        try {
            val a = org.json.JSONArray()
            list.forEach { a.put(org.json.JSONObject().put("goal", it.goal).put("text", it.text).put("ok", it.ok).put("at", it.at).put("profile", it.profile).put("steps", it.steps)) }
            f.parentFile?.mkdirs(); f.writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun clearHistory() { _history.value = emptyList(); try { historyFile?.delete() } catch (_: Exception) {} }
    data class Ui(
        val running: Boolean = false,
        val goal: String = "",
        val profile: String = "",
        val plan: List<String> = emptyList(),
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
    fun start(goal: String, fromVoice: Boolean = false, profileId: String? = null): Boolean {
        val g = goal.trim()
        if (g.isEmpty() || job?.isActive == true) return false
        val id = _ui.value.runId + 1
        val profile = profileId?.let { AgentProfiles.byId(it) } ?: AgentProfiles.pick(g)
        _ui.value = Ui(running = true, goal = g, profile = profile.label, runId = id, fromVoice = fromVoice, startedAt = System.currentTimeMillis())
        job = scope.launch {
            val result = try {
                runner.run(g, { q -> askConfirm(q) }, profile, { plan -> _ui.update { it.copy(plan = plan) } }) { step -> onStep(step) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(running = false, confirmQuestion = null, error = "Görev durduruldu.", finishedAt = System.currentTimeMillis()) }
                throw e
            } catch (e: Exception) {
                AgentResult.Failed("Ajan çalışırken beklenmeyen bir hata oluştu.")
            }
            when (result) {
                is AgentResult.Finished -> {
                    _ui.update { it.copy(running = false, answer = result.answer, confirmQuestion = null, finishedAt = System.currentTimeMillis()) }
                    addReport(Report(g, result.answer, true, System.currentTimeMillis(), profile.label, _ui.value.steps.size))
                    if (fromVoice) speak(result.answer)
                    onFinished(g, result.answer, true)
                }
                is AgentResult.Failed -> {
                    _ui.update { it.copy(running = false, error = result.reason, confirmQuestion = null, finishedAt = System.currentTimeMillis()) }
                    addReport(Report(g, result.reason, false, System.currentTimeMillis(), profile.label, _ui.value.steps.size))
                    if (fromVoice) speak(result.reason)
                    onFinished(g, result.reason, false)
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
