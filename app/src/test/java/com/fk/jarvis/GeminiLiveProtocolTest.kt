package com.fk.jarvis

import com.fk.jarvis.core.CommandIntent
import com.fk.jarvis.live.GeminiLiveProtocol
import com.fk.jarvis.live.GeminiLiveProtocol.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiLiveProtocolTest {

    @Test fun endpointUsesGoogleHostAndKey() {
        val u = GeminiLiveProtocol.endpoint(null, "AIza test")
        assertTrue(u.startsWith("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"))
        assertTrue(u.endsWith("?key=AIza+test"))
        assertEquals("wss://proxy.ornek.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            GeminiLiveProtocol.endpoint("https://proxy.ornek.com/", null))
    }

    @Test fun setupMessage() {
        val o = JSONObject(GeminiLiveProtocol.setup("gemini-3.1-flash-live-preview", "Kore", "JARVIS")).getJSONObject("setup")
        assertEquals("models/gemini-3.1-flash-live-preview", o.getString("model"))
        val gc = o.getJSONObject("generationConfig")
        assertEquals("AUDIO", gc.getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", gc.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(o.has("inputAudioTranscription") && o.has("outputAudioTranscription"))
        val fn = o.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("phone_action", fn.getString("name"))
        val enum = fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("intent").getJSONArray("enum").toString()
        assertTrue(enum.contains("open_app"))
        assertFalse("sohbet modelin kendi işi, araç değil", enum.contains("\"chat\""))
        assertTrue(o.getJSONArray("tools").getJSONObject(1).has("googleSearch"))
        assertTrue(o.getJSONObject("systemInstruction").toString().contains("ONAYLA"))
    }

    @Test fun audioMessage() {
        val o = JSONObject(GeminiLiveProtocol.audio(byteArrayOf(1, 2, 3, 4), 2)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", o.getString("mimeType"))
        assertEquals(listOf<Byte>(1, 2), Base64.getDecoder().decode(o.getString("data")).toList())
    }

    @Test fun parsesServerContent() {
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(0, 16, 0, -16))
        val ev = GeminiLiveProtocol.parse(
            """{"serverContent":{"inputTranscription":{"text":"youtube'u aç"},"outputTranscription":{"text":"Açıyorum"},
               "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]},"turnComplete":true}}"""
        )
        assertEquals(Event.InputText("youtube'u aç"), ev[0])
        assertEquals(Event.OutputText("Açıyorum"), ev[1])
        assertEquals(4, (ev[2] as Event.Audio).pcm.size)
        assertEquals(Event.TurnComplete, ev[3])
        assertEquals(listOf(Event.SetupComplete), GeminiLiveProtocol.parse("""{"setupComplete":{}}"""))
        assertEquals(listOf(Event.Interrupted), GeminiLiveProtocol.parse("""{"serverContent":{"interrupted":true}}"""))
        assertTrue(GeminiLiveProtocol.parse("bozuk").isEmpty())
    }

    @Test fun toolCallToProposalAndResponse() {
        val ev = GeminiLiveProtocol.parse(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"phone_action","args":{"intent":"open_app","arguments":{"app_name":"YouTube"}}}]}}"""
        ).single() as Event.ToolCall
        val p = GeminiLiveProtocol.toProposal(ev.args)
        assertEquals(CommandIntent.OPEN_APP, p.intent)
        assertEquals("YouTube", p.arg("app_name"))
        assertFalse("model onay gereksinimini kaldıramaz", p.requiresConfirmation)
        val r = JSONObject(GeminiLiveProtocol.toolResponse("c1", "phone_action", "BAŞARILI: açıldı"))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", r.getString("id"))
        assertEquals("BAŞARILI: açıldı", r.getJSONObject("response").getString("result"))
    }

    @Test fun levelFromPcm() {
        assertEquals(0f, GeminiLiveProtocol.level(ByteArray(100)), 0.0001f)
        val loud = ByteArray(200) { i -> if (i % 2 == 0) 0 else 0x30 } // ~12288
        assertTrue(GeminiLiveProtocol.level(loud) > 0.9f)
    }
}
