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
