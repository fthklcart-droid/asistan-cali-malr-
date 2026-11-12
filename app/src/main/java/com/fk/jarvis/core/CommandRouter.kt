package com.fk.jarvis.core

import com.fk.jarvis.accessibility.AccessibilityController
import com.fk.jarvis.apps.AndroidAppController
import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.apps.AppMatch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Güvenlik denetiminden geçmiş (SafetyPolicy.Allow ya da kullanıcı onaylı) bir öneriyi
 * uygun Android eylemine yönlendirir. Mesajlar eylemin gerçek sonucunu yansıtır.
 */
class CommandRouter(
    private val apps: AndroidAppController,
    private val calendar: com.fk.jarvis.apps.CalendarReader? = null,
    private val screen: (String, String?) -> com.fk.jarvis.apps.ActionResult = AccessibilityController::perform,
    private val memory: com.fk.jarvis.data.MemoryStore? = null,
    private val undo: com.fk.jarvis.data.UndoStack? = null,
    private val topics: com.fk.jarvis.data.TopicStore? = null,
    private val deviceStatus: (() -> String)? = null,
    private val browser: InAppBrowser? = null,
    private val tasks: com.fk.jarvis.data.TaskStore? = null,
    private val notes: com.fk.jarvis.data.NoteStore? = null,
    private val routines: com.fk.jarvis.data.RoutineStore? = null,
    /** Rutin listesi değişti: alarm yeniden kurulur. */
    private val onRoutinesChanged: () -> Unit = {},
) : CommandExecutor {

    /** Uygulama içi tarayıcı (WEB bölmesi). Bağlı değilse false döner ve dış tarayıcı kullanılır. */
    interface InAppBrowser {
        fun open(url: String): Boolean
        fun search(query: String): Boolean
    }

    private fun ok(msg: String) = com.fk.jarvis.apps.ActionResult(true, msg)
    private fun fail(msg: String) = com.fk.jarvis.apps.ActionResult(false, msg)
    override fun execute(p: CommandProposal, chosenApp: AppInfo?): RouteResult {
        val r = when (p.intent) {
            CommandIntent.OPEN_APP -> return openApp(p, chosenApp)
            CommandIntent.GO_HOME -> apps.goHome()
            CommandIntent.OPEN_CAMERA -> apps.openCamera()
            CommandIntent.WEB_SEARCH -> if (browser?.search(p.arg("query")!!) == true) ok("Aramayı uygulama içi tarayıcıda açtım.") else apps.webSearch(p.arg("query")!!)
            CommandIntent.NAVIGATE -> apps.navigate(p.arg("query")!!)
            CommandIntent.OPEN_URL -> if (browser?.open(p.arg("url")!!) == true) ok("Sayfayı uygulama içi tarayıcıda açtım.") else apps.openUrl(p.arg("url")!!)
            CommandIntent.DIAL -> apps.dial(p.arg("number")!!)
            CommandIntent.SET_ALARM -> apps.setAlarm(p.arg("hour")!!.toInt(), p.arg("minute")!!.toInt())
            CommandIntent.SET_TIMER -> apps.setTimer(p.arg("seconds")!!.toInt())
            CommandIntent.VOLUME_UP -> apps.adjustVolume(true)
            CommandIntent.VOLUME_DOWN -> apps.adjustVolume(false)
            CommandIntent.OPEN_SETTINGS -> apps.openSettings(p.target ?: "general")
            CommandIntent.SHARE_TEXT, CommandIntent.COMPOSE_MESSAGE -> {
                val target = chosenApp ?: p.arg("app_name")?.let { (apps.findApp(it) as? AppMatch.Found)?.app }
                apps.shareText(p.arg("text")!!, target)
            }
            CommandIntent.SCREEN_ACTION -> screen(p.arg("action")!!, p.arg("text"))
            CommandIntent.YOUTUBE_SEARCH -> apps.youtubeSearch(p.arg("query")!!)
            CommandIntent.OPEN_GALLERY -> apps.openGallery()
            CommandIntent.COPY_TEXT -> apps.copyToClipboard(p.arg("text")!!)
            CommandIntent.DRAFT_TEXT -> apps.copyToClipboard(p.arg("text")!!, "Taslak")
            CommandIntent.SCREENSHOT -> {
                val r = screen("screenshot", null)
                if (r.success) r else com.fk.jarvis.apps.ActionResult(false,
                    "Ekran görüntüsünü ben alamıyorum: erişilebilirlik hizmeti kapalı. Ayarlardan açabilir ya da Güç ve Ses kısma tuşlarına birlikte basabilirsin.")
            }
            CommandIntent.CLOSE_APP -> closeApp(p.arg("app_name")!!)
            CommandIntent.TODAY_AGENDA -> {
                val cal = calendar ?: return RouteResult.Done(false, "Takvim okuma bu cihazda kullanılamıyor.")
                if (!cal.hasPermission()) return RouteResult.NeedsPermission(
                    android.Manifest.permission.READ_CALENDAR,
                    "Ajandanı okuyabilmem için takvim iznine ihtiyacım var. Ekrandaki izin penceresinden izin verebilirsin; takvim bilgilerin cihazdan çıkmaz.",
                    p,
                )
                val today = java.time.LocalDate.now()
                val day = com.fk.jarvis.apps.CalendarText.parseDate(p.arg("date"), today)
                try {
                    if (day == null || day == today) ok(com.fk.jarvis.apps.AgendaFormatter.describe(cal.today()))
                    else ok(com.fk.jarvis.apps.CalendarText.describe(cal.events(day, 1), day, 1))
                } catch (e: SecurityException) { fail("Takvim okunamadı.") }
            }
            CommandIntent.CALENDAR_ADD -> return calendarAdd(p)
            CommandIntent.REMEMBER -> {
                val m = memory ?: return RouteResult.Done(false, "Hafıza kullanılamıyor.")
                when (val r = m.put(p.arg("category"), p.arg("key"), p.arg("value") ?: p.arg("text").orEmpty())) {
                    is com.fk.jarvis.data.MemoryStore.PutResult.Refused -> fail(r.reason)
                    is com.fk.jarvis.data.MemoryStore.PutResult.Saved -> {
                        undo?.push("Hafızaya kayıt: ${r.fact.value.take(40)}") { m.restore(r.fact.key, r.previous); true to "Hafızaya son eklediğim bilgiyi geri aldım." }
                        ok("Tamam, hatırlayacağım: ${r.fact.value}")
                    }
                }
            }
            CommandIntent.RECALL -> {
                val m = memory ?: return RouteResult.Done(false, "Hafıza kullanılamıyor.")
                val q = p.arg("query").orEmpty()
                val list = m.search(q, 10)
                if (q.isBlank() && list.isEmpty()) ok("Hafızamda senin hakkında henüz bir bilgi yok. \"Bunu hatırla\" diyerek bana bir şey öğretebilirsin.") else ok(m.describe(list))
            }
            CommandIntent.FORGET -> {
                val m = memory ?: return RouteResult.Done(false, "Hafıza kullanılamıyor.")
                val q = p.arg("query").orEmpty()
                val before = m.all()
                val removed = if (q == "*") before.also { m.clear() } else m.forget(q)
                if (removed.isEmpty()) fail("Hafızamda \"$q\" ile ilgili bir bilgi bulamadım.")
                else {
                    undo?.push("Hafızadan silme (${removed.size})") { before.forEach { f -> m.restore(f.key, f) }; true to "Silinen bilgileri hafızaya geri koydum." }
                    ok("${removed.size} bilgiyi hafızamdan sildim.")
                }
            }
            CommandIntent.UNDO -> {
                val u = undo ?: return RouteResult.Done(false, "Geri alma kullanılamıyor.")
                val (s, msg) = u.undoLast()
                com.fk.jarvis.apps.ActionResult(s, msg)
            }
            CommandIntent.DEVICE_STATUS -> deviceStatus?.let { ok(it()) } ?: fail("Telefon durumu okunamadı.")
            CommandIntent.MONITOR -> {
                val t = topics ?: return RouteResult.Done(false, "Konu takibi kullanılamıyor.")
                val topic = p.arg("topic").orEmpty()
                when (p.arg("action")) {
                    "add" -> t.add(topic)?.let { fail(it) }
                        ?: ok("\"$topic\" konusunu takibe aldım. Günde bir kez internette yeni gelişme arayıp varsa bildirim göndereceğim.")
                    "remove" -> if (t.remove(topic)) ok("\"$topic\" takibini bıraktım.") else fail("\"$topic\" adında takip edilen bir konu bulamadım.")
                    else -> t.all().let { l -> if (l.isEmpty()) ok("Şu an takip ettiğim bir konu yok.") else ok("Takip ettiğim konular: ${l.joinToString(", ") { it.name }}.") }
                }
            }
            CommandIntent.TASK_ADD -> {
                val ts = tasks ?: return RouteResult.Done(false, "Görevler kullanılamıyor.")
                val t = ts.add(p.arg("title").orEmpty(), p.arg("date"), p.arg("time"), p.arg("priority")?.toIntOrNull() ?: 1, p.arg("project").orEmpty())
                    ?: return RouteResult.Done(false, "Görevi ekleyemedim.")
                undo?.push("Görev: ${t.title.take(40)}") { ts.remove(t.id); true to "\"${t.title}\" görevini listeden kaldırdım." }
                val whenText = t.due?.let { d -> " (" + com.fk.jarvis.apps.CalendarText.dayLabel(java.time.LocalDate.parse(d), java.time.LocalDate.now()).lowercase(TextNormalizer.TR) + (t.time?.let { " $it" } ?: "") + ")" } ?: ""
                ok("Görevlerine ekledim: ${t.title}$whenText.")
            }
            CommandIntent.TASK_LIST -> {
                val ts = tasks ?: return RouteResult.Done(false, "Görevler kullanılamıyor.")
                val today = java.time.LocalDate.now()
                val day = com.fk.jarvis.apps.CalendarText.parseDate(p.arg("date"), today)
                if (day != null) ok(com.fk.jarvis.data.TaskStore.describe(ts.forDay(day), today, if (day == today) "Bugünkü görevlerin" else "O günkü görevlerin").replace("Açık görevin yok.", "O gün için görevin yok."))
                else ok(com.fk.jarvis.data.TaskStore.describe(ts.open(), today))
            }
            CommandIntent.TASK_DONE -> {
                val ts = tasks ?: return RouteResult.Done(false, "Görevler kullanılamıyor.")
                val hits = ts.find(p.arg("query").orEmpty())
                when {
                    hits.isEmpty() -> fail("\"${p.arg("query")}\" adında açık bir görev bulamadım.")
                    hits.size > 1 && com.fk.jarvis.core.TextNormalizer.normalize(hits[0].title) != com.fk.jarvis.core.TextNormalizer.normalize(p.arg("query").orEmpty()) ->
                        fail("Birden fazla görev eşleşti: ${hits.take(4).joinToString(", ") { it.title }}. Hangisi?")
                    else -> {
                        val t = hits[0]
                        ts.setDone(t.id, true)
                        undo?.push("Görev tamamlandı: ${t.title.take(40)}") { ts.setDone(t.id, false); true to "\"${t.title}\" görevini yeniden açtım." }
                        ok("\"${t.title}\" görevini tamamlandı olarak işaretledim. ${ts.open().size} açık görev kaldı.")
                    }
                }
            }
            CommandIntent.NOTE_ADD -> {
                val ns = notes ?: return RouteResult.Done(false, "Notlar kullanılamıyor.")
                val n = ns.add(p.arg("text").orEmpty()) ?: return RouteResult.Done(false, "Notu kaydedemedim.")
                undo?.push("Not: ${n.text.take(40)}") { ns.remove(n.id); true to "Son notu sildim." }
                ok("Not aldım.")
            }
            CommandIntent.NOTE_SEARCH -> {
                val ns = notes ?: return RouteResult.Done(false, "Notlar kullanılamıyor.")
                val list = ns.search(p.arg("query").orEmpty(), 5)
                if (list.isEmpty()) ok("Bununla ilgili bir not bulamadım.") else ok("Notların: " + list.joinToString(" — ") { it.text.take(220) })
            }
            CommandIntent.ROUTINE_ADD -> {
                val rs = routines ?: return RouteResult.Done(false, "Rutinler kullanılamıyor.")
                val r = com.fk.jarvis.data.Routine(
                    0, p.arg("title") ?: p.arg("text").orEmpty(),
                    runCatching { com.fk.jarvis.data.Routine.Kind.valueOf(p.arg("kind") ?: "ONCE") }.getOrDefault(com.fk.jarvis.data.Routine.Kind.ONCE),
                    p.arg("time").orEmpty(), p.arg("date"),
                    p.arg("days")?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
                    when (p.arg("action")) { "say" -> com.fk.jarvis.data.Routine.Action.SAY; "task" -> com.fk.jarvis.data.Routine.Action.TASK; else -> com.fk.jarvis.data.Routine.Action.REMIND },
                    p.arg("text").orEmpty(), created = System.currentTimeMillis(),
                )
                val (saved, err) = rs.add(r)
                if (saved == null) fail(err ?: "Rutini kuramadım.")
                else {
                    onRoutinesChanged()
                    undo?.push("Rutin: ${saved.title.take(40)}") { rs.remove(saved.id); onRoutinesChanged(); true to "\"${saved.title}\" rutinini kaldırdım." }
                    val what = when (saved.action) { com.fk.jarvis.data.Routine.Action.REMIND -> "Hatırlatıcı kuruldu"; com.fk.jarvis.data.Routine.Action.SAY -> "Rutin kuruldu"; else -> "Ajan rutini kuruldu" }
                    ok("$what: ${saved.title} — ${saved.scheduleText()}. Bildirim Android'in pil yönetimi nedeniyle birkaç dakika gecikebilir.")
                }
            }
            CommandIntent.ROUTINE_LIST -> {
                val rs = routines ?: return RouteResult.Done(false, "Rutinler kullanılamıyor.")
                val l = rs.all().filter { it.enabled }
                if (l.isEmpty()) ok("Kurulu bir rutin ya da hatırlatıcı yok.") else ok("Kurulu rutinler: " + l.take(8).joinToString("; ") { "${it.title} (${it.scheduleText()})" } + ".")
            }
            CommandIntent.ROUTINE_REMOVE -> {
                val rs = routines ?: return RouteResult.Done(false, "Rutinler kullanılamıyor.")
                val hits = rs.find(p.arg("query").orEmpty())
                if (hits.isEmpty()) fail("\"${p.arg("query")}\" ile eşleşen bir rutin bulamadım.")
                else {
                    hits.forEach { rs.remove(it.id) }
                    onRoutinesChanged()
                    undo?.push("Rutin silme (${hits.size})") { hits.forEach { rs.restore(it) }; onRoutinesChanged(); true to "Silinen rutinleri geri koydum." }
                    ok("${hits.size} rutini sildim: ${hits.joinToString(", ") { it.title }}.")
                }
            }
            CommandIntent.CAMERA_LOOK -> fail("Kamerayla bakmak için uygulamanın ekranda açık olması gerekiyor.")
            CommandIntent.TELL_TIME -> com.fk.jarvis.apps.ActionResult(true, "Saat " + SimpleDateFormat("HH:mm", TextNormalizer.TR).format(Date()) + ".")
            CommandIntent.TELL_DATE -> com.fk.jarvis.apps.ActionResult(true, "Bugün " + SimpleDateFormat("d MMMM yyyy, EEEE", TextNormalizer.TR).format(Date()) + ".")
            else -> com.fk.jarvis.apps.ActionResult(false, "Bu komut için bir Android eylemi tanımlı değil.")
        }
        return RouteResult.Done(r.success, r.message)
    }

    /**
     * Android, bir uygulamanın başka bir uygulamayı kapatmasına izin vermez. Yapılabilecek olan:
     * istenen uygulama öndeyse (erişilebilirlik hizmetiyle doğrulanır) ana ekrana dönerek onu arka plana almak.
     */
    private fun closeApp(name: String): com.fk.jarvis.apps.ActionResult {
        val target = (apps.findApp(name) as? AppMatch.Found)?.app
            ?: return com.fk.jarvis.apps.ActionResult(false, "\"$name\" adlı bir uygulamayı bu cihazda bulamadım.")
        val fg = AccessibilityController.foregroundPackage()
        if (fg == null) return com.fk.jarvis.apps.ActionResult(false,
            "Android başka uygulamaları kapatmama izin vermiyor. ${target.label} öndeyse ana ekran düğmesiyle çıkabilir ya da son uygulamalar ekranından kaydırarak kapatabilirsin.")
        if (fg != target.packageName) return com.fk.jarvis.apps.ActionResult(false, "${target.label} şu an ekranda açık görünmüyor.")
        val r = screen("home", null)
        return if (r.success) com.fk.jarvis.apps.ActionResult(true,
            "${target.label} uygulamasını arka plana aldım. Android tamamen kapatmaya izin vermez; istersen son uygulamalardan kaydırarak kapatabilirsin.")
        else r
    }

    /** Onaylanmış (SafetyPolicy normalleştirmiş) etkinliği takvime yazar; izin yoksa ister. */
    private fun calendarAdd(p: CommandProposal): RouteResult {
        val cal = calendar ?: return RouteResult.Done(false, "Takvim bu cihazda kullanılamıyor.")
        val ev = com.fk.jarvis.apps.NewEvent(
            p.arg("title") ?: return RouteResult.Done(false, "Etkinlik adı eksik."),
            java.time.LocalDate.parse(p.arg("date") ?: return RouteResult.Done(false, "Etkinlik tarihi eksik.")),
            p.arg("time")?.let { java.time.LocalTime.parse(it) },
            p.arg("duration_min")?.toIntOrNull() ?: 60,
            p.arg("location"),
        )
        if (!cal.hasWritePermission()) return RouteResult.NeedsPermission(
            android.Manifest.permission.WRITE_CALENDAR,
            "Etkinliği takvimine yazabilmem için takvim iznine ihtiyacım var. Ekrandaki izin penceresinden izin verebilirsin.",
            p,
        )
        val desc = com.fk.jarvis.apps.CalendarText.describeNew(ev, java.time.LocalDate.now())
        val id = try { cal.insert(ev) } catch (e: SecurityException) { null }
        if (id == null) {
            return try {
                apps.startIntent(cal.insertIntent(ev))
                RouteResult.Done(true, "Yazılabilir bir takvim bulamadım; takvim uygulamasının yeni etkinlik ekranını doldurarak açtım. Kaydetmeyi sen yapmalısın.")
            } catch (e: Exception) { RouteResult.Done(false, "Etkinliği takvime ekleyemedim.") }
        }
        undo?.push("Takvim etkinliği: ${ev.title}") {
            val ok = try { cal.delete(id) } catch (e: SecurityException) { false }
            ok to (if (ok) "\"${ev.title}\" etkinliğini takviminden kaldırdım." else "Etkinliği takvimden kaldıramadım.")
        }
        return RouteResult.Done(true, "Takvimine ekledim: $desc.")
    }

    private fun openApp(p: CommandProposal, chosen: AppInfo?): RouteResult {
        if (chosen != null) return apps.launch(chosen).let { RouteResult.Done(it.success, it.message) }
        // LLM bir paket adı önerdiyse yalnızca cihazda gerçekten kuruluysa kullanılır.
        apps.verifiedApp(p.target)?.let { return apps.launch(it).let { r -> RouteResult.Done(r.success, r.message) } }
        val name = p.arg("app_name") ?: p.target.orEmpty()
        return when (val m = apps.findApp(name)) {
            is AppMatch.Found -> apps.launch(m.app).let { RouteResult.Done(it.success, it.message) }
            is AppMatch.Ambiguous -> RouteResult.NeedsAppChoice(m.candidates.take(6), p)
            AppMatch.NotFound -> RouteResult.Done(false, "\"$name\" adlı bir uygulamayı bu cihazda bulamadım. Kurulu değilse Play Store'dan yükleyebilirsin.")
        }
    }
}
