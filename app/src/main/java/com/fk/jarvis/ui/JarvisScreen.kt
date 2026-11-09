package com.fk.jarvis.ui

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.AssistantUi
import com.fk.jarvis.data.AssistantSettings

private val Mono = FontFamily.Monospace

@Composable
fun JarvisScreen(
    avatarView: WebView,
    ui: AssistantUi,
    settings: AssistantSettings,
    micActive: Boolean,
    conversationActive: Boolean,
    providerConfigured: (String) -> Boolean,
    apiKeySaved: (String) -> Boolean,
    accessibilityOn: Boolean,
    onMic: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSendText: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onChooseApp: (AppInfo) -> Unit,
    onCancelChoice: () -> Unit,
    onDismissError: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReplay: () -> Unit,
    onSettingsChange: ((AssistantSettings) -> AssistantSettings) -> Unit,
    onSaveApiKey: (String, String?) -> Unit,
    onClearHistory: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onShareDraft: (String) -> Unit,
    onCloseDraft: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(JarvisColors.bg)) {
        AndroidView(factory = { avatarView }, modifier = Modifier.fillMaxSize())

        // ------------------------------------------------ üst çubuk
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    text = if (micActive) "● MİKROFON AÇIK" else "○ MİKROFON KAPALI",
                    active = micActive,
                    activeColor = JarvisColors.danger,
                )
                Spacer(Modifier.width(8.dp))
                val pName = com.fk.jarvis.llm.AIProviderManager.choices.firstOrNull { it.first == settings.provider }?.second ?: "?"
                Chip(
                    text = when {
                        settings.provider == "none" -> "CİHAZ İÇİ"
                        providerConfigured(settings.provider) -> "YZ: ${pName.uppercase()}"
                        else -> "${pName.uppercase()} AYARSIZ"
                    },
                    active = false,
                )
                if (conversationActive) {
                    Spacer(Modifier.width(8.dp))
                    Chip("● OTURUM", active = true, activeColor = JarvisColors.gold)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onRefreshAccessibility(); showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ayarlar", tint = JarvisColors.text)
                }
            }
            ui.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Panel(border = JarvisColors.danger) {
                    Text(err, color = JarvisColors.text, fontSize = 14.sp)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.micDenied) SmallButton("ANDROID AYARLARI") { onOpenAppSettings() }
                        SmallButton("KAPAT") { onDismissError() }
                    }
                }
            }
        }

        // ------------------------------------------------ alt alan
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Metin dökümü ve yanıt
            val heard = ui.partialTranscript.ifBlank { ui.transcript }
            if (heard.isNotBlank() || ui.response.isNotBlank()) {
                Panel {
                    if (heard.isNotBlank()) {
                        Label("SEN" + if (ui.partialTranscript.isNotBlank()) " (dinleniyor…)" else "")
                        Text(heard, color = JarvisColors.text, fontSize = 15.sp)
                    }
                    if (ui.response.isNotBlank()) {
                        if (heard.isNotBlank()) Spacer(Modifier.height(6.dp))
                        Label(settings.assistantName.uppercase() + if (ui.lastAnswerFromCloud) " · bulut" else " · cihaz")
                        Text(ui.response, color = JarvisColors.pink, fontSize = 15.sp)
                    }
                }
            }

            // Hazırlanan metin taslağı (ör. Instagram paylaşım metni)
            ui.draft?.let { draft ->
                Panel(border = JarvisColors.violet) {
                    Label("TASLAK · PANOYA KOPYALANDI")
                    Text(draft, color = JarvisColors.text, fontSize = 14.sp, modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("PAYLAŞ…", JarvisColors.gold) { onShareDraft(draft) }
                        SmallButton("KAPAT") { onCloseDraft() }
                    }
                }
            }

            // Onay
            if (ui.confirmQuestion != null && ui.appChoices.isEmpty()) {
                Panel(border = JarvisColors.gold) {
                    Label("ONAY GEREKİYOR")
                    Text(ui.confirmQuestion, color = JarvisColors.text, fontSize = 15.sp)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("ONAYLA", JarvisColors.gold) { onConfirm(true) }
                        SmallButton("İPTAL") { onConfirm(false) }
                    }
                }
            }
            // Uygulama seçimi
            if (ui.appChoices.isNotEmpty()) {
                Panel(border = JarvisColors.gold) {
                    Label("HANGİ UYGULAMA?")
                    ui.appChoices.forEachIndexed { i, app ->
                        Text(
                            "${i + 1}. ${app.label}",
                            color = JarvisColors.text,
                            modifier = Modifier.fillMaxWidth().clickable { onChooseApp(app) }.padding(vertical = 6.dp),
                        )
                    }
                    SmallButton("İPTAL") { onCancelChoice() }
                }
            }

            // Durum çipleri + avatar kontrolleri (videodaki alt çubuk)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                stateLabels.forEach { (st, label) -> Chip(label, active = ui.state == st) }
                Spacer(Modifier.width(10.dp))
                Chip("ARAYÜZ: ${if (settings.avatarStyle == "art") "ART" else "NEON"}", active = true, activeColor = JarvisColors.gold, onClick = {
                    onSettingsChange { it.copy(avatarStyle = if (it.avatarStyle == "art") "neon" else "art") }
                })
                Chip("TEKRAR OYNAT", active = false, onClick = onReplay)
                Chip("HIZ ${formatSpeed(settings.avatarSpeed)}", active = false, onClick = {
                    val next = when (settings.avatarSpeed) { 1f -> 2f; 2f -> 0.5f; else -> 1f }
                    onSettingsChange { it.copy(avatarSpeed = next) }
                })
                Chip(if (settings.avatarSound) "SES AÇIK" else "SES KAPALI", active = false, onClick = {
                    onSettingsChange { it.copy(avatarSound = !it.avatarSound) }
                })
            }

            // Yazıyla komut + mikrofon
            Row(verticalAlignment = Alignment.CenterVertically) {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Yazarak komut ver…", color = JarvisColors.dim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendText(text); text = "" }),
                    trailingIcon = {
                        IconButton(onClick = { onSendText(text); text = "" }) {
                            Icon(Icons.Filled.Send, contentDescription = "Gönder", tint = JarvisColors.violet)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = JarvisColors.panel,
                        unfocusedContainerColor = JarvisColors.panel,
                        focusedBorderColor = JarvisColors.violet,
                        unfocusedBorderColor = JarvisColors.stroke,
                        focusedTextColor = JarvisColors.text,
                        unfocusedTextColor = JarvisColors.text,
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(10.dp))
                if (ui.state == AssistantState.SPEAKING || ui.state == AssistantState.PROCESSING) {
                    RoundButton("DUR", JarvisColors.gold, onStopSpeaking)
                } else {
                    val listening = ui.state == AssistantState.LISTENING
                    RoundButton(if (listening) "TAMAM" else "KONUŞ", if (listening) JarvisColors.danger else JarvisColors.violet, onMic)
                }
                if (conversationActive && ui.state != AssistantState.SPEAKING) {
                    Spacer(Modifier.width(6.dp))
                    RoundButton("BİTİR", JarvisColors.dim, onStopSpeaking)
                }
            }
        }

        if (showSettings) {
            SettingsPanel(
                settings = settings,
                providerConfigured = providerConfigured,
                apiKeySaved = apiKeySaved,
                accessibilityOn = accessibilityOn,
                onChange = onSettingsChange,
                onSaveApiKey = onSaveApiKey,
                onClearHistory = onClearHistory,
                onOpenAccessibility = onOpenAccessibility,
                onRefreshAccessibility = onRefreshAccessibility,
                onClose = { showSettings = false },
            )
        }
    }
}

private val stateLabels = listOf(
    AssistantState.IDLE to "BEKLEME",
    AssistantState.LISTENING to "DİNLİYOR",
    AssistantState.PROCESSING to "DÜŞÜNÜYOR",
    AssistantState.SPEAKING to "KONUŞUYOR",
    AssistantState.EXECUTING to "YÜRÜTÜYOR",
    AssistantState.CONFIRMING to "ONAY",
    AssistantState.ERROR to "HATA",
)

private fun formatSpeed(v: Float) = if (v == 0.5f) "0.5x" else "${v.toInt()}x"

@Composable
fun Chip(text: String, active: Boolean, activeColor: Color = JarvisColors.violet, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.clip(shape)
            .background(if (active) activeColor.copy(alpha = 0.28f) else Color(0x99100420))
            .border(1.dp, if (active) activeColor else JarvisColors.stroke, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (active) Color.White else JarvisColors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun Panel(border: Color = JarvisColors.stroke, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(JarvisColors.panel).border(1.dp, border, shape).padding(12.dp)
    ) { content() }
}

@Composable
fun Label(text: String) {
    Text(text, color = JarvisColors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun SmallButton(text: String, color: Color = JarvisColors.violet, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, border = BorderStroke(1.dp, color)) {
        Text(text, color = color, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun RoundButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(color.copy(alpha = 0.25f))
            .border(2.dp, color, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
