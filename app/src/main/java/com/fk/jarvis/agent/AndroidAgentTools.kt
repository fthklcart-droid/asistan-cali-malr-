package com.fk.jarvis.agent

import android.Manifest
import android.content.Context
import com.fk.jarvis.apps.CalendarReader
import com.fk.jarvis.apps.CalendarText
import com.fk.jarvis.apps.NewEvent
import com.fk.jarvis.browser.BrowserController
import com.fk.jarvis.core.CommandExecutor
import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.CommandProposal
import com.fk.jarvis.core.RouteResult
import com.fk.jarvis.core.SafetyDecision
import com.fk.jarvis.core.SafetyPolicy
import com.fk.jarvis.llm.LLMProvider
import com.fk.jarvis.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Ajanın telefondaki gerçek araçları. Her araç sonucu gerçekte ne olduğunu söyler; yapılmayan iş yapılmış gibi bildirilmez.
 * Takvime yazma ve onay gerektiren telefon eylemleri kullanıcı onayı olmadan çalışmaz (SafetyPolicy).
 */
class AndroidAgentTools(
    private val context: Context,
    private val llm: () -> LLMProvider,
    private val calendar: CalendarReader,
    private val browser: BrowserController,
    private val executor: CommandExecutor,
    /** Çalışma zamanı iznini kullanıcıdan ister (izin penceresi); verilirse true. */
    private val requestPermission: suspend (String) -> Boolean,
    private val memory: com.fk.jarvis.data.MemoryStore? = null,
    private val undo: com.fk.jarvis.data.UndoStack? = null,
    private val deviceStatus: (() -> String)? = null,
    private val topics: com.fk.jarvis.data.TopicStore? = null,
    private val tasks: com.fk.jarvis.data.TaskStore? = null,
    private val notes: com.fk.jarvis.data.NoteStore? = null,
    private val routines: com.fk.jarvis.data.RoutineStore? = null,
) : AgentTools {

    /** Bu uygulama oturumunda ajanın gerçekten okuduğu sayfalar (adres -> metin); alıntı doğrulaması bunlara karşı yapılır. */
    private val readPages = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 12
    })

    override val specs = listOf(
        ToolSpec("web_research", "İnternette araştır", "query", "Güncel bilgi için internette arar ve kaynak adlarıyla kısa özet döndürür."),
        ToolSpec("browser_search", "Tarayıcıda ara", "query", "Uygulama içi tarayıcıda Google araması açar ve sonuç sayfasının metnini döndürür."),
        ToolSpec("browser_open", "Sayfa aç", "url", "Uygulama içi tarayıcıda bir http/https adresini açar ve sayfanın metnini döndürür."),
        ToolSpec("browser_read", "Sayfayı oku", "", "Tarayıcıda açık olan sayfanın başlığını, adresini ve metnini okur."),
        ToolSpec("calendar_list", "Takvimi oku", "from (YYYY-MM-DD), days (1-31)", "Cihaz takvimindeki etkinlikleri listeler."),
        ToolSpec("calendar_add", "Takvime ekle", "title, date (YYYY-MM-DD), time (HH:MM, tüm gün için boş), duration_min, location", "Cihaz takvimine etkinlik ekler.", sensitive = true),
        ToolSpec("verify_quote", "Alıntıyı doğrula", "quote", "Bir alıntının ya da sayının bu görevde okuduğun sayfalardan birinde gerçekten geçip geçmediğini kodda denetler."),
        ToolSpec("tasks_list", "Görevleri oku", "date (isteğe bağlı YYYY-MM-DD)", "Kullanıcının açık görevlerini (yapılacaklar) listeler."),
        ToolSpec("task_add", "Görev ekle", "title, date, time, priority (düşük|normal|yüksek), project", "Yapılacaklar listesine görev ekler."),
        ToolSpec("task_done", "Görevi tamamla", "query", "Adı verilen açık görevi tamamlandı olarak işaretler."),
        ToolSpec("note_save", "Not kaydet", "text", "Kullanıcının notlarına metin kaydeder (rapor, toplantı notu, taslak)."),
        ToolSpec("notes_search", "Notlarda ara", "query (boşsa son notlar)", "Kullanıcının notlarında arar."),
        ToolSpec("routine_add", "Rutin/hatırlatıcı kur", "text, time (HH:MM), date (tek seferlik), repeat (her gün|hafta içi|gün adları), action (remind|say|task)", "Belirli zamanda hatırlatma ya da tekrarlayan rutin kurar."),
        ToolSpec("routines_list", "Rutinleri oku", "", "Kurulu rutin ve hatırlatıcıları listeler."),
        ToolSpec("memory_recall", "Hafızaya bak", "query (boşsa tümü)", "Kullanıcı hakkında daha önce kaydedilmiş bilgileri arar (yerel, anında)."),
        ToolSpec("memory_save", "Hafızaya kaydet", "value, category, key", "Kullanıcının açıkça hatırlanmasını istediği kalıcı bir bilgiyi kaydeder. Gizli bilgi (parola, kart, kod) asla."),
        ToolSpec("device_status", "Telefon durumu", "", "Pil, sıcaklık, bellek, depolama ve ağ durumunu okur."),
        ToolSpec("topics_list", "Takip edilen konular", "", "Kullanıcının takip ettiği konuları ve son bilinen gelişmeleri listeler."),
        ToolSpec(
            "phone_action", "Telefon eylemi",
            "intent, target, arguments{...}",
            "Telefonda izinli bir eylem yapar. intent: open_app(app_name), youtube_search(query), navigate(query), dial(number), " +
                "set_alarm(hour, minute), set_timer(seconds), open_settings(target), open_camera, open_gallery, draft_text(text), " +
                "copy_text(text), share_text(text, app_name), compose_message(text, app_name), tell_time, tell_date.",
        ),
    )

    override suspend fun run(tool: String, args: JSONObject, confirm: suspend (String) -> Boolean): ToolOutcome = when (tool) {
        "web_research" -> research(args.optString("query"))
        "browser_search" -> {
            val q = args.optString("query").trim()
            if (q.isEmpty()) ToolOutcome(false, "HATA: query boş.")
            else onMain { browser.search(q) }.let { ok -> if (!ok) notAttached() else afterLoad("Arama açıldı") }
        }
        "browser_open" -> {
            val url = SafetyPolicy.sanitizeUrl(args.optString("url"))
            if (url == null) ToolOutcome(false, "HATA: Yalnızca http/https adresleri açılabilir.")
            else onMain { browser.open(url) }.let { ok -> if (!ok) notAttached() else afterLoad("Sayfa açıldı") }
        }
        "browser_read" -> readPage(8000)?.let { ToolOutcome(true, it) } ?: ToolOutcome(false, "HATA: Açık bir sayfa okunamadı.")
        "calendar_list" -> calendarList(args)
        "calendar_add" -> calendarAdd(args, confirm)
        "phone_action" -> phoneAction(args, confirm)
        "verify_quote" -> {
            val q = args.optString("quote")
            val url = QuoteCheck.find(q, HashMap(readPages))
            when {
                q.length < 8 -> ToolOutcome(false, "HATA: Alıntı çok kısa.")
                readPages.isEmpty() -> ToolOutcome(false, "DOĞRULANAMADI: Henüz hiçbir sayfa okumadın; önce browser_open ile kaynağı aç.")
                url != null -> ToolOutcome(true, "DOĞRULANDI: Alıntı şu sayfada geçiyor: $url")
                else -> ToolOutcome(false, "DOĞRULANAMADI: Alıntı okunan ${readPages.size} sayfanın hiçbirinde bu hâliyle geçmiyor. Kullanma ya da doğrulanamadığını belirt.")
            }
        }
        "tasks_list" -> routed(com.fk.jarvis.core.CommandIntent.TASK_LIST, args, confirm)
        "task_add" -> routed(com.fk.jarvis.core.CommandIntent.TASK_ADD, args, confirm)
        "task_done" -> routed(com.fk.jarvis.core.CommandIntent.TASK_DONE, args, confirm)
        "note_save" -> routed(com.fk.jarvis.core.CommandIntent.NOTE_ADD, args, confirm)
        "notes_search" -> notes?.search(args.optString("query"), 6)?.let { l ->
            ToolOutcome(true, if (l.isEmpty()) "İlgili not yok." else l.joinToString("\n") { "- ${it.text.take(600)}" })
        } ?: ToolOutcome(false, "HATA: Notlar kullanılamıyor.")
        "routine_add" -> routed(com.fk.jarvis.core.CommandIntent.ROUTINE_ADD, args, confirm)
        "routines_list" -> routed(com.fk.jarvis.core.CommandIntent.ROUTINE_LIST, args, confirm)
        "memory_recall" -> memory?.let { m -> ToolOutcome(true, m.search(args.optString("query"), 12).let { l -> if (l.isEmpty()) "Hafızada ilgili bilgi yok." else l.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.value}" } }) }
            ?: ToolOutcome(false, "HATA: Hafıza kapalı.")
        "memory_save" -> memory?.let { m ->
            when (val r = m.put(args.optString("category"), args.optString("key"), args.optString("value"))) {
                is com.fk.jarvis.data.MemoryStore.PutResult.Refused -> ToolOutcome(false, "HATA: ${r.reason}")
                is com.fk.jarvis.data.MemoryStore.PutResult.Saved -> {
                    undo?.push("Hafızaya kayıt: ${r.fact.value.take(40)}") { m.restore(r.fact.key, r.previous); true to "Hafızaya son eklenen bilgiyi geri aldım." }
                    ToolOutcome(true, "Kaydedildi: ${r.fact.value}")
                }
            }
        } ?: ToolOutcome(false, "HATA: Hafıza kapalı.")
        "device_status" -> deviceStatus?.let { ToolOutcome(true, onMain { it() }) } ?: ToolOutcome(false, "HATA: okunamadı.")
        "topics_list" -> topics?.all()?.let { l ->
            ToolOutcome(true, if (l.isEmpty()) "Takip edilen konu yok." else l.joinToString("\n") { "- ${it.name}" + (it.lastSummary.takeIf { s -> s.isNotBlank() }?.let { s -> ": $s" } ?: "") })
        } ?: ToolOutcome(false, "HATA: Konu takibi kullanılamıyor.")
        else -> ToolOutcome(false, "HATA: bilinmeyen araç")
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun notAttached() = ToolOutcome(false, "HATA: Uygulama içi tarayıcı şu an hazır değil (uygulama ekranda açık olmalı).")

    private suspend fun afterLoad(prefix: String): ToolOutcome {
        browser.awaitLoaded()
        val page = readPage(3500)
        return if (page != null) ToolOutcome(true, "$prefix.\n$page") else ToolOutcome(true, "$prefix ama sayfa metni okunamadı (yükleniyor olabilir).")
    }

    /** Araç argümanlarını (düz JSON) izinli bir komuta çevirir; aynı güvenlik denetimi ve yönlendiriciden geçer. */
    private suspend fun routed(intent: com.fk.jarvis.core.CommandIntent, args: JSONObject, confirm: suspend (String) -> Boolean): ToolOutcome {
        val a = mutableMapOf<String, String>()
        val keys = args.keys()
        while (keys.hasNext()) { val k = keys.next(); val v = args.opt(k); if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) a[k] = v.toString() }
        return runProposal(CommandProposal(intent, arguments = a, source = CommandProposal.Source.LLM), confirm)
    }

    private suspend fun readPage(max: Int): String? {
        val full = browser.readPage(20_000) ?: return null
        if (full.url.isNotBlank() && full.text.isNotBlank()) readPages[full.url] = full.text
        val p = full.copy(text = full.text.take(max))
        return "Başlık: ${p.title}\nAdres: ${p.url}\nMetin:\n${p.text}"
    }

    private suspend fun research(query: String): ToolOutcome {
        if (query.isBlank()) return ToolOutcome(false, "HATA: query boş.")
        val p = llm()
        if (!p.supportsResearch) return ToolOutcome(false, "HATA: Seçili sağlayıcı internet araştırmasını desteklemiyor; browser_search kullan.")
        return try {
            ToolOutcome(true, p.research(query.take(500), mapOf("dil" to "tr-TR")))
        } catch (e: LlmException) {
            ToolOutcome(false, "HATA: ${e.userMessage()}")
        }
    }

    private suspend fun ensure(permission: String): Boolean {
        val has = if (permission == Manifest.permission.WRITE_CALENDAR) calendar.hasWritePermission() else calendar.hasPermission()
        return has || requestPermission(permission)
    }

    private suspend fun calendarList(args: JSONObject): ToolOutcome {
        val today = LocalDate.now()
        val from = CalendarText.parseDate(args.optString("from").ifBlank { "bugün" }, today) ?: today
        val days = args.optInt("days", 1).coerceIn(1, 31)
        if (!ensure(Manifest.permission.READ_CALENDAR)) return ToolOutcome(false, "HATA: Takvim okuma izni verilmedi.")
        return try {
            val list = withContext(Dispatchers.IO) { calendar.events(from, days) }
            ToolOutcome(true, CalendarText.describe(list, from, days))
        } catch (e: SecurityException) {
            ToolOutcome(false, "HATA: Takvim okunamadı.")
        }
    }

    private suspend fun calendarAdd(args: JSONObject, confirm: suspend (String) -> Boolean): ToolOutcome {
        val a = buildMap {
            for (k in listOf("title", "date", "time", "duration_min", "location")) args.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { put(k, it) }
        }
        val d = SafetyPolicy.evaluate(CommandProposal(CommandIntent.CALENDAR_ADD, arguments = a, source = CommandProposal.Source.LLM))
        val p = when (d) {
            is SafetyDecision.Reject -> return ToolOutcome(false, "HATA: ${d.reason}")
            is SafetyDecision.Allow -> d.proposal
            is SafetyDecision.Confirm -> if (confirm(d.question)) d.proposal else return ToolOutcome(false, "${AgentRunner.DECLINED}: Kullanıcı etkinlik eklemeyi onaylamadı.")
        }
        val ev = NewEvent(
            p.arg("title")!!, LocalDate.parse(p.arg("date")!!), p.arg("time")?.let { LocalTime.parse(it) },
            p.arg("duration_min")?.toIntOrNull() ?: 60, p.arg("location"),
        )
        if (!ensure(Manifest.permission.WRITE_CALENDAR)) {
            onMain { context.startActivity(calendar.insertIntent(ev)) }
            return ToolOutcome(true, "Takvim yazma izni verilmediği için takvim uygulamasının yeni etkinlik ekranını doldurarak açtım; kaydetmeyi kullanıcı yapacak (henüz kaydedilmedi).")
        }
        val id = try { withContext(Dispatchers.IO) { calendar.insert(ev) } } catch (e: SecurityException) { null }
        if (id != null) undo?.push("Takvim etkinliği: ${ev.title}") {
            val ok = try { calendar.delete(id) } catch (e: SecurityException) { false }
            ok to (if (ok) "\"${ev.title}\" etkinliğini takviminden kaldırdım." else "Etkinliği takvimden kaldıramadım.")
        }
        return if (id != null) ToolOutcome(true, "Etkinlik takvime eklendi: ${CalendarText.describeNew(ev, LocalDate.now())}.")
        else {
            onMain { context.startActivity(calendar.insertIntent(ev)) }
            ToolOutcome(true, "Yazılabilir takvim bulunamadı; takvim uygulamasının yeni etkinlik ekranını doldurarak açtım (kaydetmeyi kullanıcı yapacak).")
        }
    }

    private suspend fun phoneAction(args: JSONObject, confirm: suspend (String) -> Boolean): ToolOutcome {
        val p0 = com.fk.jarvis.live.GeminiLiveProtocol.toProposal(args)
        if (p0.intent in BLOCKED) return ToolOutcome(false, "HATA: '${p0.intent.wire}' phone_action ile kullanılamaz; uygun aracı kullan.")
        return runProposal(p0, confirm)
    }

    private suspend fun runProposal(p0: CommandProposal, confirm: suspend (String) -> Boolean): ToolOutcome {
        var p = when (val d = SafetyPolicy.evaluate(p0)) {
            is SafetyDecision.Reject -> return ToolOutcome(false, "HATA: ${d.reason}")
            is SafetyDecision.Allow -> d.proposal
            is SafetyDecision.Confirm -> if (confirm(d.question)) d.proposal else return ToolOutcome(false, "${AgentRunner.DECLINED}: Kullanıcı işlemi onaylamadı.")
        }
        repeat(2) {
            when (val r = onMain { executor.execute(p) }) {
                is RouteResult.Done -> return ToolOutcome(r.success, if (r.success) "BAŞARILI: ${r.message}" else "BAŞARISIZ: ${r.message}")
                is RouteResult.NeedsAppChoice -> return ToolOutcome(false,
                    "BAŞARISIZ: Birden fazla uygulama eşleşti: ${r.candidates.joinToString { it.label }}. app_name'i bunlardan biriyle tam olarak ver.")
                is RouteResult.NeedsPermission -> {
                    if (!requestPermission(r.permission)) return ToolOutcome(false, "BAŞARISIZ: Gerekli izin verilmedi.")
                    p = r.proposal
                }
            }
        }
        return ToolOutcome(false, "BAŞARISIZ: İşlem tamamlanamadı.")
    }

    companion object {
        private val BLOCKED = setOf(
            CommandIntent.AGENT_TASK, CommandIntent.RESEARCH, CommandIntent.CHAT, CommandIntent.CLARIFY, CommandIntent.STOP,
            CommandIntent.END_SESSION, CommandIntent.REPEAT_LAST, CommandIntent.CALENDAR_ADD, CommandIntent.TODAY_AGENDA, CommandIntent.UNKNOWN, CommandIntent.CAPABILITIES,
        )
    }
}
