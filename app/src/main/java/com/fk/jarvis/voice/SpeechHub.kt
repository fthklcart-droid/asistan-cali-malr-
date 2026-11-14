package com.fk.jarvis.voice

import android.os.Handler
import android.os.Looper

/**
 * Konuşma tanımanın tek kapısı. İki motoru yönetir ve hangisinin çalışacağına karar verir:
 *  - [system]: telefonun kendi tanıyıcısı (birden çok strateji dener),
 *  - [cloud]: uygulamanın kendi kaydedicisi + Gemini ile yazıya dökme (telefon hizmetinden bağımsız).
 *
 * Mod ([mode]): "auto" (varsayılan) · "system" (yalnızca telefon) · "gemini" (her zaman Gemini).
 * "auto"da telefon tanıyıcısı SERT hata verirse (Türkçe yok, hizmet bozuk, mikrofon çakışması, hizmetin izni yok)
 * kullanıcıya hata göstermeden AYNI dinleme turunda sıradaki yola geçilir: önce telefonun diğer tanıyıcıları,
 * sonra Gemini. Çalışan yol sonraki turlarda da kullanılır. Hiçbiri çalışmazsa hata, Android kodu ile birlikte bildirilir.
 * Tüm çağrılar ana iş parçacığından yapılmalıdır.
 */
class SpeechHub(
    private val system: JarvisSpeechRecognizer,
    private val cloud: CloudSttEngine,
    private val mode: () -> String,
    private val hasMicPermission: () -> Boolean,
) : SpeechToTextProvider {
    private val main = Handler(Looper.getMainLooper())
    private var turn = 0
    private var active: SpeechToTextProvider? = null

    /** Bu uygulama oturumunda telefon tanıyıcısı sert hata verdi ve Gemini'ye geçildi. */
    var switchedToCloud = false
        private set
    /** Son başarısızlığın ayrıntısı (ör. "telefonun varsayılan tanıyıcısı: ERROR_CLIENT (5)"); yoksa null. */
    var lastFailure: String? = null
        private set
    /** Telefon tanıyıcısının bu oturumdaki son sert hatası (SİSTEM ekranında gösterilir). */
    var systemFailure: String? = null
        private set

    private fun cloudWanted(): Boolean = when (mode()) {
        "gemini" -> cloud.isAvailable
        "system" -> false
        else -> cloud.isAvailable && (switchedToCloud || !system.isAvailable)
    }

    override val isAvailable: Boolean
        get() = system.isAvailable || (mode() != "system" && cloud.isAvailable)

    /** Şu an hangi motorun kullanılacağını anlatır (SİSTEM ekranı). */
    fun describe(): String = when {
        cloudWanted() && mode() == "gemini" -> "Gemini ile tanıma (ayarlardan seçildi)"
        cloudWanted() && !system.isAvailable -> "Gemini ile tanıma (telefonda tanıma hizmeti yok)"
        cloudWanted() -> "Gemini ile tanıma — telefon tanıyıcısı hata verdi" + (systemFailure?.let { ": $it" } ?: "")
        system.isAvailable -> "Telefon: ${system.strategyName}" + (systemFailure?.let { " (önceki hata: $it)" } ?: "") +
            (if (mode() != "system" && !cloud.isAvailable) " · Gemini yedeği için anahtar gir" else "")
        else -> "Tanıma yok — MENÜ → SUNUCU'ya Gemini anahtarı gir"
    }

    override fun start(config: SttConfig, listener: SttListener) {
        val id = ++turn
        lastFailure = null
        if (cloudWanted()) startCloud(id, config, listener) else startSystem(id, config, listener, retried = false)
    }

    private fun startSystem(id: Int, config: SttConfig, listener: SttListener, retried: Boolean) {
        active = system
        var heard = false
        system.start(config, object : SttListener {
            override fun onReady() { if (id == turn) listener.onReady() }
            override fun onLevel(level: Float) { if (id == turn) listener.onLevel(level) }
            override fun onPartial(text: String) { if (id == turn) { heard = true; listener.onPartial(text) } }
            override fun onFinal(text: String) { if (id == turn) listener.onFinal(text) }
            override fun onError(error: SttError) {
                if (id != turn) return
                // İzin hatası ama bizim iznimiz var: mikrofon izni olmayan, tanıma hizmetinin kendisidir (ör. Google uygulaması).
                val servicePermission = error == SttError.PERMISSION && hasMicPermission()
                val hard = servicePermission || error in HARD
                if (!hard) { listener.onError(error); return }
                val why = system.strategyName + ": " + (if (servicePermission) "tanıma hizmetinin mikrofon izni yok" else system.lastErrorText ?: error.name)
                systemFailure = why; lastFailure = why
                val canCloud = mode() != "system" && cloud.isAvailable
                when {
                    // Kullanıcı konuşmuşken gelen hata: bu turu kurtaramayız; sonraki tur için yolu değiştir.
                    heard -> {
                        if (!system.nextStrategy() && canCloud) switchedToCloud = true
                        listener.onError(if (servicePermission) SttError.OTHER else error)
                    }
                    // Mikrofon çakışması / meşgul: kısa bekleyip aynı yolu bir kez daha dene.
                    !retried && (error == SttError.AUDIO || error == SttError.BUSY) ->
                        main.postDelayed({ if (id == turn) startSystem(id, config, listener, retried = true) }, 450)
                    // Telefonun sıradaki tanıyıcısı.
                    system.nextStrategy() -> main.postDelayed({ if (id == turn) startSystem(id, config, listener, retried = false) }, 150)
                    // Gemini ile tanıma: aynı turda, kullanıcıya hata göstermeden.
                    canCloud -> { switchedToCloud = true; startCloud(id, config, listener) }
                    else -> listener.onError(if (servicePermission) SttError.OTHER else error)
                }
            }
        })
    }

    private fun startCloud(id: Int, config: SttConfig, listener: SttListener) {
        active = cloud
        runCatching { system.destroy() }
        cloud.start(config, object : SttListener {
            override fun onReady() { if (id == turn) listener.onReady() }
            override fun onLevel(level: Float) { if (id == turn) listener.onLevel(level) }
            override fun onPartial(text: String) { if (id == turn) listener.onPartial(text) }
            override fun onFinal(text: String) { if (id == turn) listener.onFinal(text) }
            override fun onError(error: SttError) {
                if (id != turn) return
                if (error in HARD || error == SttError.NETWORK) lastFailure = cloud.lastErrorText ?: lastFailure
                listener.onError(error)
            }
        })
    }

    override fun stop() { active?.stop() }

    override fun cancel() {
        turn++
        active?.cancel()
    }

    override fun destroy() {
        turn++
        system.destroy(); cloud.destroy()
        active = null
    }

    private companion object {
        val HARD = setOf(SttError.OTHER, SttError.LANGUAGE, SttError.AUDIO, SttError.BUSY, SttError.UNAVAILABLE)
    }
}
