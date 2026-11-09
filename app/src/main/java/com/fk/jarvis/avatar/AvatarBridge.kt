package com.fk.jarvis.avatar

import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fk.jarvis.core.AssistantState
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Avatar (assets/avatar, WebView içinde Canvas) ile asistan durumları arasındaki adaptör.
 * Avatarın gerçek JS API'sini kullanır: window.avatar.setState / setLevel / replay / setSpeed / setSound.
 * Avatarda dudak senkronizasyonu yok; konuşma seviyesi [setLevel] ile 0..1 olarak verilir.
 */
class AvatarBridge {
    private var ref = WeakReference<WebView>(null)
    private var lastLevel = -1f
    private var lastLevelAt = 0L
    private var lastState: String? = null
    @Volatile var introDone = false
        private set

    fun attach(webView: WebView) {
        ref = WeakReference(webView)
        webView.addJavascriptInterface(JsCallbacks(), "Android")
    }

    fun onPageLoaded() {
        lastState?.let { js("avatar.setState('$it')") }
    }

    fun setState(state: AssistantState, micDenied: Boolean = false) {
        val s = if (micDenied && state == AssistantState.IDLE) "micoff" else toJs(state)
        if (s == lastState) return
        lastState = s
        js("avatar.setState('$s')")
    }

    /** Ses seviyesi: en fazla ~30 kez/sn ve anlamlı değişimde gönderilir. */
    fun setLevel(v: Float) {
        val now = SystemClock.uptimeMillis()
        if (abs(v - lastLevel) < 0.03f && v != 0f) return
        if (now - lastLevelAt < 33 && v != 0f) return
        lastLevel = v; lastLevelAt = now
        js("avatar.setLevel(${"%.3f".format(java.util.Locale.US, v)})")
    }

    /** Arayüz stilini değiştirir: "neon" (mor neon figür) veya "art" (turkuaz büst + dağlar). */
    fun loadStyle(style: String) {
        val w = ref.get() ?: return
        val s = if (style == "art") "art" else "neon"
        if (s == loadedStyle) return
        loadedStyle = s
        introDone = false
        w.loadUrl("file:///android_asset/avatar/index.html?style=$s")
    }
    private var loadedStyle: String? = null

    fun replay() = js("avatar.replay()")
    fun setSpeed(speed: Float) = js("avatar.setSpeed(${"%.2f".format(java.util.Locale.US, speed)})")
    fun setSound(on: Boolean) = js("avatar.setSound($on)")

    private fun js(code: String) {
        val w = ref.get() ?: return
        w.post { w.evaluateJavascript("window.avatar && $code", null) }
    }

    private inner class JsCallbacks {
        @JavascriptInterface
        fun onIntroDone() { introDone = true }
    }

    companion object {
        fun toJs(s: AssistantState): String = when (s) {
            AssistantState.IDLE -> "idle"
            AssistantState.LISTENING -> "listening"
            AssistantState.PROCESSING -> "thinking"
            AssistantState.SPEAKING -> "talking"
            AssistantState.EXECUTING -> "executing"
            AssistantState.CONFIRMING -> "confirming"
            AssistantState.ERROR -> "error"
        }
    }
}
