package com.fk.jarvis.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fk.jarvis.core.SafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Uygulama içi tarayıcı (WEB bölmesi). Tek bir kalıcı WebView kullanılır; asistan ve ajan da aynı tarayıcıyı sürer.
 * Güvenlik: yalnızca http/https sayfalar içeride açılır; dosya erişimi kapalı, Safe Browsing açık, karışık içerik engelli,
 * kamera/mikrofon/konum istekleri reddedilir. tel:, mailto:, geo:, market: bağlantıları ilgili uygulamada açılır.
 */
class BrowserController {
    data class State(
        val url: String = "",
        val title: String = "",
        val progress: Int = 100,
        val loading: Boolean = false,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val error: String? = null,
        val secure: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _show = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Asistan/ajan bir sayfa açtığında arayüzün WEB bölmesine geçmesi için. */
    val showRequests: SharedFlow<Unit> = _show.asSharedFlow()

    private var ref = WeakReference<WebView>(null)
    private var pendingUrl: String? = null

    val isAttached: Boolean get() = ref.get() != null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(w: WebView) {
        ref = WeakReference(w)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme?.lowercase()) {
                    "http", "https" -> false
                    "tel", "mailto", "geo", "market", "sms" -> { external(view, u); true }
                    "intent" -> {
                        // Yalnızca sitenin verdiği tarayıcı yedek adresine gidilir; rastgele uygulama başlatılmaz.
                        val fallback = runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
                        SafetyPolicy.sanitizeUrl(fallback)?.let { view.loadUrl(it) }
                        true
                    }
                    else -> true
                }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                _state.update { it.copy(url = url.orEmpty(), loading = true, error = null, secure = url?.startsWith("https://") == true) }
                nav(view)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                _state.update { it.copy(url = url.orEmpty(), loading = false, progress = 100, title = view.title.orEmpty()) }
                nav(view)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) _state.update { it.copy(loading = false, error = "Sayfa açılamadı: ${error.description}") }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { _state.update { it.copy(progress = newProgress) } }
            override fun onReceivedTitle(view: WebView, title: String?) { _state.update { it.copy(title = title.orEmpty()) } }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        w.setDownloadListener { url, _, _, _, _ -> external(w, Uri.parse(url)) }
        pendingUrl?.let { pendingUrl = null; w.loadUrl(it) } ?: run { if (w.url == null) w.loadUrl(HOME) }
    }

    private fun nav(v: WebView) = _state.update { it.copy(canGoBack = v.canGoBack(), canGoForward = v.canGoForward()) }

    private fun external(v: WebView, u: Uri) {
        try { v.context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }

    /** Adres çubuğu girdisi: adres gibi görünüyorsa açılır, değilse Google'da aranır. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val looksLikeUrl = !t.contains(' ') && (t.contains('.') || t.startsWith("http"))
        val url = if (looksLikeUrl) SafetyPolicy.sanitizeUrl(t) else null
        load(url ?: searchUrl(t))
    }

    /** Asistan/ajan için: güvenli adresi açar ve WEB bölmesini gösterir. Tarayıcı bağlı değilse false. */
    fun open(raw: String, show: Boolean = true): Boolean {
        val url = SafetyPolicy.sanitizeUrl(raw) ?: return false
        if (!load(url)) return false
        if (show) _show.tryEmit(Unit)
        return true
    }

    fun search(query: String, show: Boolean = true): Boolean {
        if (!load(searchUrl(query))) return false
        if (show) _show.tryEmit(Unit)
        return true
    }

    private fun load(url: String): Boolean {
        val w = ref.get()
        if (w == null) { pendingUrl = url; return false }
        w.post { w.loadUrl(url) }
        _state.update { it.copy(url = url, loading = true, error = null) }
        return true
    }

    fun back() { ref.get()?.let { if (it.canGoBack()) it.goBack() } }
    fun forward() { ref.get()?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() { ref.get()?.reload() }
    fun stop() { ref.get()?.stopLoading() }
    fun home() { load(HOME) }
    fun canGoBack(): Boolean = ref.get()?.canGoBack() == true

    /** Sayfanın yüklenmesini bekler (en fazla [timeoutMs]). */
    suspend fun awaitLoaded(timeoutMs: Long = 15_000): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { !it.loading } ; true } ?: false

    /** Açık sayfanın başlık, adres ve görünür metnini okur (ajanın "sayfayı oku" aracı). */
    suspend fun readPage(maxChars: Int = 8000): PageText? = withContext(Dispatchers.Main) {
        val w = ref.get() ?: return@withContext null
        val js = "(function(){try{var b=document.body?document.body.innerText:'';" +
            "return JSON.stringify({t:document.title||'',u:location.href,b:b.replace(/\\n{3,}/g,'\\n\\n').slice(0,$maxChars)});}catch(e){return '';}})()"
        val raw = suspendCancellableCoroutine<String?> { cont -> w.evaluateJavascript(js) { cont.resume(it) } } ?: return@withContext null
        try {
            val inner = JSONTokener(raw).nextValue() as? String ?: return@withContext null
            if (inner.isEmpty()) return@withContext null
            val o = JSONObject(inner)
            PageText(o.optString("t"), o.optString("u"), o.optString("b"))
        } catch (e: Exception) { null }
    }

    data class PageText(val title: String, val url: String, val text: String)

    companion object {
        const val HOME = "https://www.google.com/"
        fun searchUrl(q: String) = "https://www.google.com/search?q=" + URLEncoder.encode(q.take(300), "UTF-8") + "&hl=tr"
    }
}
