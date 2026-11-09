package com.fk.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk.jarvis.data.AssistantSettings
import com.fk.jarvis.llm.AIProviderManager

@Composable
fun SettingsPanel(
    settings: AssistantSettings,
    providerConfigured: (String) -> Boolean,
    apiKeySaved: (String) -> Boolean,
    accessibilityOn: Boolean,
    onChange: ((AssistantSettings) -> AssistantSettings) -> Unit,
    onSaveApiKey: (String, String?) -> Unit,
    onClearHistory: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onClose: () -> Unit,
) {
    val pid = settings.provider
    var keySaved by remember(pid) { mutableStateOf(apiKeySaved(pid)) }
    var keyInput by remember(pid) { mutableStateOf("") }
    var model by remember(pid) { mutableStateOf(settings.models[pid] ?: "") }
    var baseUrl by remember(pid) { mutableStateOf(settings.baseUrls[pid] ?: "") }
    var name by remember { mutableStateOf(settings.assistantName) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pid) { info = null }

    Column(
        Modifier.fillMaxSize().background(Color(0xF2000306))
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AYARLAR", color = JarvisColors.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            SmallButton("KAPAT") { onClose() }
        }

        Panel {
            Label("ARAYÜZ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("ART · NEON MAVİ", active = settings.avatarStyle != "neon", onClick = { onChange { it.copy(avatarStyle = "art") } })
                Chip("NEON · MOR", active = settings.avatarStyle == "neon", onClick = { onChange { it.copy(avatarStyle = "neon") } })
            }
            Spacer(Modifier.height(8.dp))
            Label("ASİSTAN ADI")
            OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            SmallButton("KAYDET") { onChange { it.copy(assistantName = name.ifBlank { "JARVIS" }) } }
        }

        Panel {
            Label("YAPAY ZEKÂ SAĞLAYICISI")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AIProviderManager.choices.forEach { (id, label) ->
                    Chip(label.uppercase(), active = pid == id, onClick = { onChange { it.copy(provider = id) } })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cihaz içi komutlar (uygulama açma, saat, alarm, ses, ekran komutları...) internetsiz çalışır. Sağlayıcıya yalnızca " +
                    "cihazın anlayamadığı isteklerde söylediğin metin, son birkaç konuşma satırı ve saat bilgisi gönderilir; ses kaydı, " +
                    "takvim ve ekran içeriği gönderilmez.",
                color = JarvisColors.dim, fontSize = 13.sp,
            )
            if (pid != "none") {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (providerConfigured(pid)) "Durum: hazır" else "Durum: ayarlanmadı",
                    color = if (providerConfigured(pid)) JarvisColors.gold else JarvisColors.dim, fontSize = 13.sp,
                )
                if (pid != "local") {
                    Label("API ANAHTARI")
                    Text(
                        if (keySaved) "Kayıtlı (cihazda Android Keystore ile şifreli)" else "Kayıtlı değil",
                        color = if (keySaved) JarvisColors.gold else JarvisColors.dim, fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it.trim() }, singleLine = true,
                        placeholder = { Text(keyHint(pid)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("KAYDET") {
                            if (keyInput.isNotBlank()) { onSaveApiKey(pid, keyInput); keyInput = ""; keySaved = apiKeySaved(pid); info = "Anahtar kaydedildi." }
                        }
                        SmallButton("SİL", JarvisColors.danger) { onSaveApiKey(pid, null); keySaved = apiKeySaved(pid); info = "Anahtar silindi." }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Label("MODEL")
                OutlinedTextField(
                    value = model, onValueChange = { model = it.trim() }, singleLine = true,
                    placeholder = { Text(AIProviderManager.defaultModel(pid)) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Label(if (pid == "local") "YEREL MODEL SUNUCUSU (OpenAI uyumlu)" else "ÖZEL SUNUCU / PROXY (isteğe bağlı)")
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it.trim() }, singleLine = true,
                    placeholder = { Text(if (pid == "local") "http://192.168.1.20:11434/v1" else "https://proxy.ornek.com") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (pid == "local") "Ollama, LM Studio gibi OpenAI uyumlu bir sunucu. http yalnızca yerel ağ adreslerinde kabul edilir."
                    else "Anahtarı kendi sunucunda tutmak için: buraya proxy adresini yaz, anahtarı boş bırak. Yalnızca https.",
                    color = JarvisColors.dim, fontSize = 12.sp,
                )
                SmallButton("MODEL VE SUNUCUYU KAYDET") {
                    onChange {
                        it.copy(
                            models = it.models + (pid to model),
                            baseUrls = if (baseUrl.isBlank()) it.baseUrls - pid else it.baseUrls + (pid to baseUrl),
                        )
                    }
                    info = "Kaydedildi."
                }
            }
            info?.let { Text(it, color = JarvisColors.gold, fontSize = 13.sp) }
        }

        Panel {
            Label("SESLİ ETKİLEŞİM")
            SwitchRow("Kesintisiz sohbet (yanıttan sonra otomatik dinle)", settings.conversationMode) { b -> onChange { it.copy(conversationMode = b) } }
            SwitchRow("Konuşurken araya girebileyim (barge-in)", settings.bargeIn) { b -> onChange { it.copy(bargeIn = b) } }
            Text(
                "Araya girme açıkken asistan konuşurken mikrofon da açıktır; hoparlör sesi yüksekse yanlış tetiklenebilir. Kulaklıkla en iyi sonuç verir.",
                color = JarvisColors.dim, fontSize = 12.sp,
            )
            SwitchRow("Soru sorduktan sonra otomatik dinle", settings.listenAfterQuestion) { b -> onChange { it.copy(listenAfterQuestion = b) } }
            Text("Sessizlik süresi: %.1f sn".format(settings.silenceTimeoutMs / 1000f), color = JarvisColors.text, fontSize = 14.sp)
            Slider(value = settings.silenceTimeoutMs.toFloat(), valueRange = 500f..5000f, onValueChange = { v -> onChange { it.copy(silenceTimeoutMs = v.toInt()) } })
            Text("Konuşma hızı: %.1fx".format(settings.speechRate), color = JarvisColors.text, fontSize = 14.sp)
            Slider(value = settings.speechRate, valueRange = 0.6f..1.6f, onValueChange = { v -> onChange { it.copy(speechRate = v) } })
            Text("Ses tonu: %.1f".format(settings.speechPitch), color = JarvisColors.text, fontSize = 14.sp)
            Slider(value = settings.speechPitch, valueRange = 0.6f..1.6f, onValueChange = { v -> onChange { it.copy(speechPitch = v) } })
        }

        Panel {
            Label("UYANDIRMA SÖZCÜĞÜ")
            SwitchRow("\"${settings.assistantName}\" deyince dinlemeye başla", settings.wakeWord) { b -> onChange { it.copy(wakeWord = b) } }
            SwitchRow("Ekran kapalıyken de dinle (pil tüketir)", settings.wakeWhileScreenOff) { b -> onChange { it.copy(wakeWhileScreenOff = b) } }
            Text(
                "Açıldığında bildirim çubuğunda kalıcı bir \"mikrofon kullanımda olabilir\" bildirimi görünür (Android kuralı). " +
                    "Bildirimdeki Durdur ile kapatabilirsin. Sistem konuşma tanıyıcısını sürekli kullandığı için pil tüketir; " +
                    "cihaz içi tanıma varsa (Android 13+) internetsiz çalışır. Telefon yeniden başlatılınca uygulamayı bir kez açman gerekir.",
                color = JarvisColors.dim, fontSize = 12.sp,
            )
        }

        Panel {
            Label("GEÇMİŞ VE GİZLİLİK")
            SwitchRow("Konuşma geçmişini cihazda sakla (yalnızca metin)", settings.keepHistory) { b -> onChange { it.copy(keepHistory = b) } }
            SmallButton("GEÇMİŞİ TEMİZLE", JarvisColors.danger) { onClearHistory(); info = "Geçmiş temizlendi." }
            Text(
                "Ses kayıtları saklanmaz. Parolalar kaydedilmez ve hiçbir yere gönderilmez. Mikrofon açıkken üstte kırmızı gösterge yanar.",
                color = JarvisColors.dim, fontSize = 13.sp,
            )
        }

        Panel {
            Label("EKRAN KONTROLÜ (ERİŞİLEBİLİRLİK)")
            Text(
                if (accessibilityOn) "Durum: açık" else "Durum: kapalı",
                color = if (accessibilityOn) JarvisColors.gold else JarvisColors.dim, fontSize = 13.sp,
            )
            Text(
                "Açarsan: geri/ana ekran, kaydırma, ekrandaki bir yazıya dokunma, seçili alana yazı yazma, seçili metni kopyalama ve " +
                    "ekran görüntüsü alma komutları çalışır; diğer uygulamaların üzerinde küçük bir mikrofon düğmesi görünür. Ekran içeriği " +
                    "yalnızca istediğin işlem anında okunur, saklanmaz ve gönderilmez. Gönder/öde/sil gibi işlemler ve yazı yazma her zaman " +
                    "onay ister; parola alanlarına yazılmaz. Bazı uygulamalar (ör. bankacılık) bu kontrolü engelleyebilir.",
                color = JarvisColors.dim, fontSize = 13.sp,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("AYARLARI AÇ") { onOpenAccessibility() }
                SmallButton("DURUMU YENİLE") { onRefreshAccessibility() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun keyHint(id: String) = when (id) {
    "gemini" -> "aistudio.google.com → Get API key"
    "openai" -> "platform.openai.com → API keys"
    "claude" -> "console.anthropic.com → API Keys"
    else -> "API anahtarı"
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = JarvisColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
