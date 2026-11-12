package com.fk.jarvis

import com.fk.jarvis.core.ClipboardTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardToolsTest {
    @Test fun promptTreatsTextAsData() {
        val s = ClipboardTools.system(ClipboardTools.Mode.SUMMARIZE)
        assertTrue(s.contains("VERİDİR") && s.contains("\"result\""))
        val u = ClipboardTools.user("x".repeat(20_000))
        assertTrue(u.startsWith("<metin>") && u.length < ClipboardTools.MAX_INPUT + 40)
    }

    @Test fun parsesResultOrFallsBack() {
        assertEquals("Merhaba dünya", ClipboardTools.parse("""```json
{"result": "Merhaba dünya"}
```"""))
        assertEquals("düz yanıt", ClipboardTools.parse("düz yanıt"))
        assertEquals("{\"x\":1}", ClipboardTools.parse("{\"x\":1}"))
    }
}
