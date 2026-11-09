package com.fk.jarvis

import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.apps.AppMatch
import com.fk.jarvis.apps.AppMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val installed = listOf(
        AppInfo("YouTube", "com.google.android.youtube"),
        AppInfo("YouTube Music", "com.google.android.apps.youtube.music"),
        AppInfo("WhatsApp", "com.whatsapp"),
        AppInfo("WhatsApp Business", "com.whatsapp.w4b"),
        AppInfo("Maps", "com.google.android.apps.maps"),
        AppInfo("CapCut", "com.lemon.lvoverseas"),
        AppInfo("Lightroom", "com.adobe.lrmobile"),
    )

    private fun found(s: String) = (AppMatcher.match(s, installed) as AppMatch.Found).app.packageName

    @Test fun exact() {
        assertEquals("com.google.android.youtube", found("youtube"))
        assertEquals("com.whatsapp", found("whatsapp"))
        assertEquals("com.lemon.lvoverseas", found("capcut"))
    }
    @Test fun accusativeWithoutApostrophe() = assertEquals("com.whatsapp", found("whatsappı"))
    @Test fun turkishAlias() {
        assertEquals("com.google.android.apps.maps", found("google haritalar"))
        assertEquals("com.google.android.apps.maps", found("haritalar"))
    }
    @Test fun notInstalled() {
        assertTrue(AppMatcher.match("photoshop", installed) is AppMatch.NotFound)
        assertTrue(AppMatcher.match("instagram", installed) is AppMatch.NotFound)
    }
    @Test fun ambiguous() {
        val only = listOf(AppInfo("Notes", "a.notes"), AppInfo("Notes", "b.notes"))
        assertTrue(AppMatcher.match("notes", only) is AppMatch.Ambiguous)
    }
}
