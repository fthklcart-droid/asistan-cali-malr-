package com.fk.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import com.fk.jarvis.JarvisApp
import com.fk.jarvis.core.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Kullanıcının Ayarlar > Erişilebilirlik'ten açıkça etkinleştirdiği hizmet.
 * - Olayları sürekli okumaz/saklamaz; yalnızca istenen işlem anında aktif pencereyi inceler.
 * - Başka uygulamalar açıkken sesli komut verebilmek için ekranın kenarında küçük bir
 *   mikrofon düğmesi gösterir (erişilebilirlik katmanı; ek "üstte göster" izni gerekmez).
 */
class JarvisAccessibilityService : AccessibilityService() {

    private var overlay: ImageView? = null
    private var scope: CoroutineScope? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Bilinçli olarak boş: ekran içeriği arka planda izlenmez.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeOverlay()
        scope?.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC3A1070.toInt())
            setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFC89BFF.toInt())
        }
        val v = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = bg
            contentDescription = "SRİUS JARVIS mikrofon"
            val pad = size / 5
            setPadding(pad, pad, pad, pad)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * resources.displayMetrics.density).toInt()
        }

        // Sürükleyerek taşınabilir; kısa dokunuş dinlemeyi başlatır/durdurur.
        var downY = 0f; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > 12) moved = true
                    lp.y = startY + dy
                    wm.updateViewLayout(v, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onOverlayTap()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            overlay = null
        }

        val orchestrator = (application as JarvisApp).core
        scope?.launch {
            orchestrator.ui.collect { ui ->
                val color = when (ui.state) {
                    AssistantState.LISTENING -> 0xEEB0306A.toInt()
                    AssistantState.PROCESSING, AssistantState.EXECUTING -> 0xEE5A2AA0.toInt()
                    AssistantState.SPEAKING -> 0xEE7A3AD0.toInt()
                    AssistantState.CONFIRMING -> 0xEEA07020.toInt()
                    AssistantState.ERROR -> 0xEEA02030.toInt()
                    AssistantState.IDLE -> 0xCC3A1070.toInt()
                }
                bg.setColor(color)
            }
        }
    }

    private fun onOverlayTap() {
        val orchestrator = (application as JarvisApp).core
        when (orchestrator.ui.value.state) {
            AssistantState.LISTENING -> orchestrator.stopListening()
            AssistantState.SPEAKING -> orchestrator.startListening() // konuşmayı kesip dinle
            else -> orchestrator.startVoiceSession()
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
