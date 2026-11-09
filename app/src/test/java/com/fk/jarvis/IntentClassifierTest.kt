package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.core.IntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentClassifierTest {
    private val c = IntentClassifier()
    private fun intent(s: String) = c.classify(s)?.intent

    @Test fun hearing() = assertEquals(CommandIntent.CHAT, intent("JARVIS, beni duyuyor musun?"))

    @Test fun openApps() {
        for ((spoken, app) in listOf(
            "YouTube'u aç" to "youtube",
            "WhatsApp'ı aç." to "whatsapp",
            "Google Haritalar'ı aç" to "google haritalar",
            "CapCut'ı aç" to "capcut",
            "Lightroom'u aç" to "lightroom",
            "Photoshop'u aç" to "photoshop",
            "jarvis spotify uygulamasını aç" to "spotify",
        )) {
            val p = c.classify(spoken)!!
            assertEquals(spoken, CommandIntent.OPEN_APP, p.intent)
            assertEquals(spoken, app, p.arg("app_name"))
        }
    }

    @Test fun camera() = assertEquals(CommandIntent.OPEN_CAMERA, intent("Kamerayı aç"))
    @Test fun volume() {
        assertEquals(CommandIntent.VOLUME_DOWN, intent("Sesini kıs"))
        assertEquals(CommandIntent.VOLUME_UP, intent("sesini yükselt"))
    }
    @Test fun repeat() {
        assertEquals("user", c.classify("Son söylediğimi tekrar et")!!.arg("who"))
        assertEquals("assistant", c.classify("tekrar eder misin, tekrarla")!!.arg("who"))
    }
    @Test fun timeDate() {
        assertEquals(CommandIntent.TELL_TIME, intent("saat kaç"))
        assertEquals(CommandIntent.TELL_DATE, intent("bugün günlerden ne"))
    }
    @Test fun timer() {
        val p = c.classify("5 dakikalık zamanlayıcı kur")!!
        assertEquals(CommandIntent.SET_TIMER, p.intent)
        assertEquals("300", p.arg("seconds"))
        assertEquals("90", c.classify("1 dakika 30 saniye sayaç başlat")!!.arg("seconds"))
        assertEquals(CommandIntent.CLARIFY, intent("zamanlayıcı kur"))
    }
    @Test fun alarm() {
        val p = c.classify("saat 7'de alarm kur")!!
        assertEquals(CommandIntent.SET_ALARM, p.intent)
        assertEquals("7", p.arg("hour")); assertEquals("0", p.arg("minute"))
        val q = c.classify("07:45 için alarm kur")!!
        assertEquals("7", q.arg("hour")); assertEquals("45", q.arg("minute"))
        assertEquals("30", c.classify("saat 6 buçukta alarm")!!.arg("minute"))
    }
    @Test fun screen() {
        assertEquals("back", c.classify("geri git")!!.arg("action"))
        assertEquals("scroll_down", c.classify("aşağı kaydır")!!.arg("action"))
        val click = c.classify("Gönder'e bas")!!
        assertEquals("click", click.arg("action")); assertEquals("gönder", click.arg("text"))
        assertEquals("type", c.classify("şunu yaz merhaba dünya")!!.arg("action"))
    }
    @Test fun settings() = assertEquals("wifi", c.classify("wifi ayarlarını aç")!!.target)
    @Test fun webSearch() = assertEquals("kotlin coroutine", c.classify("kotlin coroutine diye ara")!!.arg("query"))
    @Test fun dial() = assertEquals("05551234567", c.classify("0555 123 45 67 numarasını ara")!!.arg("number"))
    @Test fun home() = assertEquals(CommandIntent.GO_HOME, intent("ana ekrana dön"))

    @Test fun newCommands() {
        assertEquals(CommandIntent.TODAY_AGENDA, intent("JARVIS, bugün neler yapmam gerekiyor?"))
        assertEquals(CommandIntent.RESEARCH, intent("JARVIS, bana günlük haberleri oku."))
        assertEquals(CommandIntent.RESEARCH, intent("JARVIS, internette araştırma yap."))
        assertNull(c.classify("internette araştırma yap")!!.arg("query"))
        assertEquals("elektrikli arabalar", c.classify("elektrikli arabalar hakkında araştırma yap")!!.arg("query"))
        assertEquals(CommandIntent.SCREENSHOT, intent("JARVIS, ekran görüntüsü almamı sağla."))
        assertEquals(CommandIntent.COPY_TEXT, intent("JARVIS, bu metni kopyala."))
        assertEquals(CommandIntent.OPEN_GALLERY, intent("galeriyi aç"))
        assertEquals(CommandIntent.OPEN_APP, intent("JARVIS, CapCut'ı başlat."))
        assertEquals("capcut", c.classify("JARVIS, CapCut'ı başlat.")!!.arg("app_name"))
        assertEquals(CommandIntent.OPEN_APP, intent("Chrome'u aç"))
        assertEquals(CommandIntent.VOLUME_UP, intent("JARVIS, sesini yükselt."))
        val yt = c.classify("YouTube'da lofi müzik ara")!!
        assertEquals(CommandIntent.YOUTUBE_SEARCH, yt.intent); assertEquals("lofi müzik", yt.arg("query"))
        val close = c.classify("YouTube'u kapat")!!
        assertEquals(CommandIntent.CLOSE_APP, close.intent); assertEquals("youtube", close.arg("app_name"))
        assertEquals(CommandIntent.END_SESSION, intent("görüşürüz"))
        assertEquals(CommandIntent.SHARE_TEXT, intent("paylaşım ekranını aç"))
        assertEquals(CommandIntent.OPEN_APP, intent("JARVIS YOUTUBE'U AÇ")) // büyük harf "JARVIS" -> "jarvıs"
    }

    @Test fun freeFormGoesToLlm() {
        assertNull(c.classify("Bana bir Instagram paylaşım metni hazırla"))
        assertNull(c.classify("Bana kısa bir özet ver."))
        assertNull(c.classify("annemi ara")) // belirsiz: kişi araması yerel olarak yapılmaz
    }
}
