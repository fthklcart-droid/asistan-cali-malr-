package com.fk.jarvis.core

import com.fk.jarvis.accessibility.AccessibilityActionProvider
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
    private val screen: (String, String?) -> com.fk.jarvis.apps.ActionResult = AccessibilityActionProvider::perform,
) : CommandExecutor {
    override fun execute(p: CommandProposal, chosenApp: AppInfo?): RouteResult {
        val r = when (p.intent) {
            CommandIntent.OPEN_APP -> return openApp(p, chosenApp)
            CommandIntent.GO_HOME -> apps.goHome()
            CommandIntent.OPEN_CAMERA -> apps.openCamera()
            CommandIntent.WEB_SEARCH -> apps.webSearch(p.arg("query")!!)
            CommandIntent.NAVIGATE -> apps.navigate(p.arg("query")!!)
            CommandIntent.OPEN_URL -> apps.openUrl(p.arg("url")!!)
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
            CommandIntent.TELL_TIME -> com.fk.jarvis.apps.ActionResult(true, "Saat " + SimpleDateFormat("HH:mm", TextNormalizer.TR).format(Date()) + ".")
            CommandIntent.TELL_DATE -> com.fk.jarvis.apps.ActionResult(true, "Bugün " + SimpleDateFormat("d MMMM yyyy, EEEE", TextNormalizer.TR).format(Date()) + ".")
            else -> com.fk.jarvis.apps.ActionResult(false, "Bu komut için bir Android eylemi tanımlı değil.")
        }
        return RouteResult.Done(r.success, r.message)
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
