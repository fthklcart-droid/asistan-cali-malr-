package com.fk.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk.jarvis.llm.AIProviderManager

private data class ProviderInfo(val id: String, val name: String, val desc: String, val keyHint: String, val research: Boolean)

private val providers = listOf(
    ProviderInfo("gemini", "GEMINI", "Google AI Studio", "aistudio.google.com → Get API key", true),
    ProviderInfo("openai", "OPENAI", "OpenAI API", "platform.openai.com → API keys", true),
    ProviderInfo("claude", "CLAUDE", "Anthropic API", "console.anthropic.com → API Keys", true),
    ProviderInfo("local", "YEREL", "Ollama / LM Studio", "", false),
    ProviderInfo("none", "CİHAZ İÇİ", "İnternetsiz komutlar", "", false),
)

/** SUNUCU: yapay zekâ sağlayıcısı, anahtar, model, sunucu/proxy adresi ve gerçek bağlantı testi. */
@Composable
fun ServerScreen(s: HoloState, a: HoloActions) {
    val st = s.settings
    val active = st.provider
    var editing by remember { mutableStateOf(active) }
    val info = providers.first { it.id == editing }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("SUNUCU", "Yapay zekâ bağlantısı, model ve sunucu ayarları")

        // Sağlayıcı kartları (2 sütun)
        providers.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    ProviderCard(
                        p = p,
                        modifier = Modifier.weight(1f),
                        selected = p.id == editing,
                        active = p.id == active,
                        ready = p.id == "none" || s.providerReady(p.id),
                        onClick = { editing = p.id },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (editing != active) {
            HoloButton("${info.name} SAĞLAYICISINI ETKİNLEŞTİR", accent = JarvisColors.gold, filled = true, modifier = Modifier.fillMaxWidth()) {
                a.updateSettings { it.copy(provider = editing) }
            }
        }

        if (editing == "none") {
            HoloPanel(title = "CİHAZ İÇİ MOD") {
                BodyText(
                    "Hiçbir veri internete gönderilmez. Uygulama açma, arama, alarm, zamanlayıcı, ses, ayarlar, saat/tarih, " +
                        "ajanda ve ekran komutları çalışır. Serbest sohbet, haber ve araştırma için bir bulut sağlayıcısı seç.",
                )
            }
        } else {
            ProviderConfig(editing, info, s, a)
            if (editing == "gemini") LivePanel(s, a)
        }

        HoloPanel(title = "VERİ VE GÜVENLİK") {
            BodyText(
                "Sağlayıcıya yalnızca söylediğin cümlenin metni, son 6 konuşma satırı ve saat gönderilir. Ses, takvim, ekran içeriği " +
                    "ve uygulama listesi gönderilmez. Anahtarlar telefonda Android Keystore ile şifrelenir. Bulut adresleri yalnızca " +
                    "https olabilir; http yalnızca yerel ağdaki model sunucusu için kabul edilir.",
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderCard(p: ProviderInfo, modifier: Modifier, selected: Boolean, active: Boolean, ready: Boolean, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val accent = if (selected) JarvisColors.violet else JarvisColors.stroke
    Column(
        modifier.background(if (selected) JarvisColors.violet.copy(alpha = 0.14f) else Color(0x66000A12), shape)
            .border(1.dp, accent, shape)
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = if (selected) Color.White else JarvisColors.text, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            StatusDot(if (ready) Color(0xFF3DFFB0) else JarvisColors.gold, pulse = active)
        }
        Spacer(Modifier.height(4.dp))
        Text(p.desc, color = JarvisColors.dim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            when { active -> "● ETKİN"; ready -> "HAZIR"; else -> "AYARLANMADI" },
            color = when { active -> JarvisColors.violet; ready -> Color(0xFF3DFFB0); else -> JarvisColors.gold },
            fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ProviderConfig(id: String, info: ProviderInfo, s: HoloState, a: HoloActions) {
    val st = s.settings
    var key by remember(id) { mutableStateOf("") }
    var model by remember(id) { mutableStateOf(st.models[id] ?: "") }
    var base by remember(id) { mutableStateOf(st.baseUrls[id] ?: "") }
    var saved by remember(id) { mutableStateOf(s.keySaved(id)) }
    var note by remember(id) { mutableStateOf<String?>(null) }

    if (id != "local") {
        HoloPanel(title = "API ANAHTARI", trailing = { StatusPill(if (saved) "KAYITLI" else "YOK", if (saved) Color(0xFF3DFFB0) else JarvisColors.gold) }) {
            HoloField(key, { key = it.trim() }, placeholder = info.keyHint, password = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoloButton("KAYDET", filled = true, enabled = key.isNotBlank()) {
                    a.saveKey(id, key); key = ""; saved = s.keySaved(id); note = "Anahtar şifrelenerek kaydedildi."
                }
                if (saved) HoloButton("SİL", accent = JarvisColors.danger) { a.saveKey(id, null); saved = s.keySaved(id); note = "Anahtar silindi." }
            }
            Spacer(Modifier.height(6.dp))
            BodyText("Anahtar ekrana bir daha yazdırılmaz, loglanmaz, APK'ya eklenmez.", size = 12)
        }
    }

    HoloPanel(title = if (id == "local") "YEREL MODEL SUNUCUSU" else "MODEL VE SUNUCU") {
        FieldLabel("MODEL")
        Spacer(Modifier.height(4.dp))
        HoloField(model, { model = it.trim() }, placeholder = AIProviderManager.defaultModel(id).ifBlank { "llama3.1" })
        Spacer(Modifier.height(10.dp))
        FieldLabel(if (id == "local") "SUNUCU ADRESİ (OpenAI uyumlu)" else "ÖZEL SUNUCU / PROXY (isteğe bağlı)")
        Spacer(Modifier.height(4.dp))
        HoloField(base, { base = it.trim() }, placeholder = if (id == "local") "http://192.168.1.20:11434/v1" else "https://proxy.ornek.com")
        Spacer(Modifier.height(6.dp))
        BodyText(
            if (id == "local") "Ollama için: bilgisayarda OLLAMA_HOST=0.0.0.0 ile başlat, telefon aynı Wi-Fi'da olsun. http yalnızca yerel ağ adreslerinde kabul edilir."
            else "Anahtarı kendi sunucunda tutmak için proxy adresini yaz ve anahtarı boş bırak; proxy anahtarı ekler. Yalnızca https.",
            size = 12,
        )
        Spacer(Modifier.height(10.dp))
        HoloButton("KAYDET", filled = true) {
            a.updateSettings {
                it.copy(
                    models = if (model.isBlank()) it.models - id else it.models + (id to model),
                    baseUrls = if (base.isBlank()) it.baseUrls - id else it.baseUrls + (id to base),
                )
            }
            note = "Model ve sunucu ayarı kaydedildi."
        }
        note?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFF3DFFB0), fontSize = 12.sp) }
    }

    val test = s.connectionTest?.takeIf { it.providerId == id }
    HoloPanel(title = "BAĞLANTI TESTİ", accent = when (test?.ok) { true -> Color(0xFF3DFFB0); false -> JarvisColors.danger; null -> JarvisColors.violet }) {
        BodyText("Kayıtlı ayarlarla sağlayıcıya gerçek bir kısa istek gönderir ve yanıt süresini ölçer.", size = 12)
        Spacer(Modifier.height(10.dp))
        HoloButton(if (test?.running == true) "TEST EDİLİYOR…" else "BAĞLANTIYI TEST ET", enabled = test?.running != true && s.providerReady(id)) { a.testProvider(id) }
        if (!s.providerReady(id)) { Spacer(Modifier.height(6.dp)); BodyText("Önce anahtarı ya da sunucu adresini kaydet.", JarvisColors.gold, 12) }
        if (test != null && !test.running) {
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Sonuç", if (test.ok == true) "BAŞARILI" else "BAŞARISIZ", if (test.ok == true) Color(0xFF3DFFB0) else JarvisColors.danger)
            test.ms?.let { KeyValueRow("Yanıt süresi", "$it ms") }
            Spacer(Modifier.height(4.dp))
            BodyText(test.message, JarvisColors.text, 13)
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("İnternet araştırması", if (info.research) "DESTEKLENİR" else "YOK")
    }
    Spacer(Modifier.width(1.dp))
}

/** Gemini Live: gerçek zamanlı sesli konuşma ayarları (Gemini API anahtarını kullanır). */
@Composable
private fun LivePanel(s: HoloState, a: HoloActions) {
    val st = s.settings
    var model by remember { mutableStateOf(st.liveModel) }
    val pink = Color(0xFFFF4D8D)
    HoloPanel(title = "GEMINI LIVE · GERÇEK ZAMANLI", accent = pink, trailing = {
        StatusPill(if (s.liveActive) "CANLI" else if (st.liveMode) "AÇIK" else "KAPALI", if (s.liveActive || st.liveMode) pink else JarvisColors.dim, pulse = s.liveActive)
    }) {
        HoloSwitchRow(
            "Gerçek zamanlı konuşma",
            "KONUŞ düğmesi canlı oturum açar: ses doğrudan Gemini'ye akar, Gemini kendi sesiyle anında yanıt verir. Söz kesilebilir.",
            st.liveMode,
        ) { b -> a.updateSettings { it.copy(liveMode = b) } }
        Spacer(Modifier.height(8.dp))
        FieldLabel("LIVE MODELİ")
        Spacer(Modifier.height(4.dp))
        HoloField(model, { model = it.trim() }, placeholder = com.fk.jarvis.live.GeminiLiveProtocol.DEFAULT_MODEL)
        Spacer(Modifier.height(6.dp))
        HoloButton("MODELİ KAYDET", accent = pink) {
            a.updateSettings { it.copy(liveModel = model.ifBlank { com.fk.jarvis.live.GeminiLiveProtocol.DEFAULT_MODEL }) }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("SES")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.fk.jarvis.live.GeminiLiveProtocol.VOICES.forEach { v ->
                HoloChip(v.uppercase(), active = st.liveVoice == v, accent = pink) { a.updateSettings { it.copy(liveVoice = v) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        BodyText(
            "Canlı modda konuşma sesi (yalnızca oturum sürerken) Google'a gider; telefon işlemleri yine aynı güvenlik kurallarından geçer, " +
                "onay gerekenler ekrandaki ONAYLA ile yapılır. \"Araya girme\" kapalıysa model konuşurken mikrofon gönderilmez " +
                "(hoparlör yankısı modeli kesmesin diye). Live önizleme modelidir; Google model adını değiştirirse buradan güncelle.",
            size = 12,
        )
    }
}
