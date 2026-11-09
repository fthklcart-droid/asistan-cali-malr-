package com.fk.jarvis.ui

import androidx.compose.foundation.background
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
import com.fk.jarvis.llm.GeminiProvider

@Composable
fun SettingsPanel(
    settings: AssistantSettings,
    apiKeySaved: () -> Boolean,
    accessibilityOn: Boolean,
    onChange: ((AssistantSettings) -> AssistantSettings) -> Unit,
    onSaveApiKey: (String?) -> Unit,
    onClearHistory: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onClose: () -> Unit,
) {
    var keySaved by remember { mutableStateOf(apiKeySaved()) }
    var keyInput by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(settings.geminiModel) }
    var name by remember { mutableStateOf(settings.assistantName) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Color(0xF2080212))
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AYARLAR", color = JarvisColors.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            SmallButton("KAPAT") { onClose() }
        }

        Panel {
            Label("ASİSTAN ADI")
            OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            SmallButton("KAYDET") { onChange { it.copy(assistantName = name.ifBlank { "JARVIS" }) } }
        }

        Panel {
            Label("ARAYÜZ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("NEON", active = settings.avatarStyle != "art", onClick = { onChange { it.copy(avatarStyle = "neon") } })
                Chip("ART", active = settings.avatarStyle == "art", onClick = { onChange { it.copy(avatarStyle = "art") } })
            }
        }

        Panel {
            Label("YAPAY ZEKÂ SAĞLAYICISI")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("GEMINI (BULUT)", active = settings.provider == "gemini", onClick = { onChange { it.copy(provider = "gemini") } })
                Chip("YALNIZCA CİHAZ İÇİ", active = settings.provider != "gemini", onClick = { onChange { it.copy(provider = "none") } })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cihaz içi: saat, tarih, uygulama açma, alarm, zamanlayıcı, ses, arama, ayarlar ve ekran komutları " +
                    "internet olmadan cihazda yorumlanır.\nBulut (Gemini): cihazın anlayamadığı istekler ve sohbet; " +
                    "yalnızca söylediğin metin, son birkaç konuşma satırı ve saat bilgisi Google'a gönderilir. Ses kaydı gönderilmez.",
                color = JarvisColors.dim, fontSize = 13.sp,
            )
        }

        Panel {
            Label("GEMINI API ANAHTARI")
            Text(
                if (keySaved) "Durum: kayıtlı (cihazda şifreli saklanıyor)" else "Durum: kayıtlı değil",
                color = if (keySaved) JarvisColors.gold else JarvisColors.dim, fontSize = 13.sp,
            )
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it.trim() }, singleLine = true,
                placeholder = { Text("aistudio.google.com → Get API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("KAYDET") {
                    if (keyInput.isNotBlank()) {
                        onSaveApiKey(keyInput); keyInput = ""; keySaved = apiKeySaved(); info = "Anahtar kaydedildi."
                    }
                }
                SmallButton("SİL", JarvisColors.danger) { onSaveApiKey(null); keySaved = apiKeySaved(); info = "Anahtar silindi." }
            }
            Spacer(Modifier.height(8.dp))
            Label("MODEL")
            OutlinedTextField(value = model, onValueChange = { model = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SmallButton("MODELİ KAYDET") { onChange { it.copy(geminiModel = model.ifBlank { GeminiProvider.DEFAULT_MODEL }) }; info = "Model kaydedildi." }
            info?.let { Text(it, color = JarvisColors.gold, fontSize = 13.sp) }
        }

        Panel {
            Label("KONUŞMA")
            Text("Sessizlik süresi: %.1f sn".format(settings.silenceTimeoutMs / 1000f), color = JarvisColors.text, fontSize = 14.sp)
            Slider(
                value = settings.silenceTimeoutMs.toFloat(), valueRange = 500f..5000f,
                onValueChange = { v -> onChange { it.copy(silenceTimeoutMs = v.toInt()) } },
            )
            Text("Konuşma hızı: %.1fx".format(settings.speechRate), color = JarvisColors.text, fontSize = 14.sp)
            Slider(
                value = settings.speechRate, valueRange = 0.6f..1.6f,
                onValueChange = { v -> onChange { it.copy(speechRate = v) } },
            )
            SwitchRow("Soru sorduktan sonra otomatik dinle", settings.listenAfterQuestion) { b -> onChange { it.copy(listenAfterQuestion = b) } }
        }

        Panel {
            Label("GEÇMİŞ VE GİZLİLİK")
            SwitchRow("Konuşma geçmişini cihazda sakla (yalnızca metin)", settings.keepHistory) { b -> onChange { it.copy(keepHistory = b) } }
            SmallButton("GEÇMİŞİ TEMİZLE", JarvisColors.danger) { onClearHistory(); info = "Geçmiş temizlendi." }
            Text(
                "Ses kayıtları saklanmaz. Mikrofon yalnızca KONUŞ düğmesine bastığında ya da asistan soru sorduktan sonra kısa süre açılır; " +
                    "açıkken üstte kırmızı gösterge yanar. Uyandırma sözcüğü (sürekli dinleme) bu sürümde yoktur.",
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
                "Açarsan: geri/ana ekran, kaydırma, ekrandaki bir yazıya dokunma ve seçili alana yazı yazma komutları çalışır; " +
                    "ayrıca diğer uygulamaların üzerinde küçük bir mikrofon düğmesi görünür. Ekran içeriği yalnızca istediğin " +
                    "işlem anında okunur, saklanmaz ve gönderilmez. Gönder/öde/sil gibi işlemler ve yazı yazma her zaman onay ister; " +
                    "parola alanlarına yazılmaz. Bazı uygulamalar (ör. bankacılık) bu tür kontrolü engelleyebilir.",
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

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = JarvisColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
