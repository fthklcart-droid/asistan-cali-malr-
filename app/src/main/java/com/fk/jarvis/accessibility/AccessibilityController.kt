package com.fk.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.fk.jarvis.apps.ActionResult

/**
 * Seviye B — ekran etkileşimi. Yalnızca kullanıcı Ayarlar > Erişilebilirlik'ten
 * SRİUS JARVIS hizmetini kendisi açtıysa çalışır. Ekran içeriği yalnızca istenen tek işlem
 * için, o anda okunur; saklanmaz ve hiçbir yere gönderilmez.
 * Parola alanlarına yazılmaz; CAPTCHA/biyometri gibi korumalar aşılmaya çalışılmaz.
 */
object AccessibilityController {

    fun isAvailable(): Boolean = JarvisAccessibilityService.instance != null

    fun perform(action: String, text: String?): ActionResult {
        val svc = JarvisAccessibilityService.instance
            ?: return ActionResult(false,
                if (!com.fk.jarvis.BuildConfig.SCREEN_CONTROL) "Bu basit sürümde ekran kontrolü yok. Bu komut için uygulamanın tam sürümünü kurmalısın."
                else "Ekran kontrolü için erişilebilirlik hizmeti kapalı. Ayarlar'dan SRİUS JARVIS erişilebilirlik hizmetini açabilirsin.")
        return when (action) {
            "back" -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK, "Geri gidildi.")
            "home" -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME, "Ana ekrana dönüldü.")
            "recents" -> global(svc, AccessibilityService.GLOBAL_ACTION_RECENTS, "Son uygulamalar açıldı.")
            "notifications" -> global(svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Bildirimler açıldı.")
            "scroll_down" -> scroll(svc, true)
            "scroll_up" -> scroll(svc, false)
            "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= 28)
                global(svc, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Ekran görüntüsü alındı; galeride Ekran görüntüleri klasöründe bulabilirsin.")
            else ActionResult(false, "Bu Android sürümünde ekran görüntüsünü ben alamıyorum.")
            "copy" -> copy(svc)
            "click" -> click(svc, text.orEmpty())
            "type" -> type(svc, text.orEmpty())
            else -> ActionResult(false, "Bu ekran işlemi desteklenmiyor.")
        }
    }

    /** Şu an önde olan uygulamanın paket adı (yalnızca istenen işlem anında okunur). */
    fun foregroundPackage(): String? = JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()

    private fun copy(svc: AccessibilityService): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, "Ekran içeriğine erişilemedi.")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirst(root) { it.isFocused || it.isAccessibilityFocused }
            ?: return ActionResult(false, "Kopyalanacak seçili bir metin bulamadım. Önce metni seç.")
        if (node.isPassword) return ActionResult(false, "Parola alanlarından metin kopyalamıyorum.")
        return if (node.performAction(AccessibilityNodeInfo.ACTION_COPY)) ActionResult(true, "Seçili metin panoya kopyalandı.")
        else ActionResult(false, "Bu alandaki metni kopyalayamadım.")
    }

    private fun global(svc: AccessibilityService, id: Int, ok: String): ActionResult =
        if (svc.performGlobalAction(id)) ActionResult(true, ok) else ActionResult(false, "Android bu işlemi gerçekleştirmedi.")

    private fun scroll(svc: AccessibilityService, forward: Boolean): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, "Ekran içeriğine erişilemedi.")
        val target = findFirst(root) { it.isScrollable && it.isVisibleToUser }
            ?: return ActionResult(false, "Bu ekranda kaydırılabilir bir alan bulamadım.")
        val ok = target.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        return if (ok) ActionResult(true, if (forward) "Aşağı kaydırıldı." else "Yukarı kaydırıldı.")
        else ActionResult(false, "Daha fazla kaydırılamıyor.")
    }

    private fun click(svc: AccessibilityService, text: String): ActionResult {
        if (text.isBlank()) return ActionResult(false, "Neye dokunacağımı anlayamadım.")
        val root = svc.rootInActiveWindow ?: return ActionResult(false, "Ekran içeriğine erişilemedi.")
        val nodes = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        if (nodes.isEmpty()) return ActionResult(false, "Ekranda \"$text\" yazan bir öğe bulamadım.")
        if (nodes.size > 1 && nodes.none { it.text?.toString()?.equals(text, ignoreCase = true) == true }) {
            return ActionResult(false, "Ekranda \"$text\" içeren birden fazla öğe var. Daha net söyler misin?")
        }
        val node = nodes.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true } ?: nodes[0]
        if (node.isPassword) return ActionResult(false, "Parola alanlarıyla etkileşime girmiyorum.")
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        val ok = cur?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        return if (ok) ActionResult(true, "\"$text\" öğesine dokunuldu.") else ActionResult(false, "\"$text\" öğesine dokunulamadı.")
    }

    private fun type(svc: AccessibilityService, text: String): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, "Ekran içeriğine erişilemedi.")
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirst(root) { it.isEditable && it.isVisibleToUser }
            ?: return ActionResult(false, "Yazı yazılabilecek bir alan bulamadım. Önce alana dokun.")
        if (field.isPassword) return ActionResult(false, "Parola alanlarına yazı yazmıyorum.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) ActionResult(true, "Metin alana yazıldı. Göndermeyi sen yapmalısın.") else ActionResult(false, "Bu alana yazı yazılamadı.")
    }

    private fun findFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            val n = queue.removeFirst()
            visited++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }
}
