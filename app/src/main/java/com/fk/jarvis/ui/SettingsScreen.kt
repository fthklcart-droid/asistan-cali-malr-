package com.fk.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** AYARLAR: arayüz, ses, uyandırma sözcüğü, gizlilik. (Sunucu ayarları SUNUCU sekmesinde.) */
@Composable
fun SettingsScreen(s: HoloState, a: HoloActions) {
    val st = s.settings
    var name by remember { mutableStateOf(st.assistantName) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("AYARLAR", "Arayüz, ses ve gizlilik")

        HoloPanel(title = "ARAYÜZ") {
            FieldLabel("AVATAR")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoloChip("ART · HOLOGRAFİK MAVİ", active = st.avatarStyle != "neon") { a.updateSettings { it.copy(avatarStyle = "art") } }
                HoloChip("NEON · MOR", active = st.avatarStyle == "neon") { a.updateSettings { it.copy(avatarStyle = "neon") } }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("ANİMASYON HIZI")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f to "0.5x", 1f to "1x", 2f to "2x").forEach { (v, l) ->
                    HoloChip(l, active = st.avatarSpeed == v) { a.updateSettings { it.copy(avatarSpeed = v) } }
                }
            }
            HoloSwitchRow("Avatar ambiyans sesi", checked = st.avatarSound) { b -> a.updateSettings { it.copy(avatarSound = b) } }
            HoloButton("AÇILIŞ ANİMASYONUNU OYNAT", accent = JarvisColors.dim) { a.replayAvatar() }
        }

        HoloPanel(title = "ASİSTAN") {
            FieldLabel("ADI (UYANDIRMA SÖZCÜĞÜ DE BUDUR)")
            Spacer(Modifier.height(4.dp))
            HoloField(name, { name = it.take(20) }, placeholder = "JARVIS")
            Spacer(Modifier.height(8.dp))
            HoloButton("KAYDET", filled = true) { a.updateSettings { it.copy(assistantName = name.ifBlank { "JARVIS" }) } }
        }

        HoloPanel(title = "SESLİ ETKİLEŞİM") {
            HoloSwitchRow("Kesintisiz sohbet", "Yanıttan sonra otomatik olarak yeniden dinler", st.conversationMode) { b -> a.updateSettings { it.copy(conversationMode = b) } }
            HoloSwitchRow("Araya girme (barge-in)", "Asistan konuşurken konuşursan susar ve seni dinler. Kulaklıkla en iyisi.", st.bargeIn) { b -> a.updateSettings { it.copy(bargeIn = b) } }
            HoloSwitchRow("Sorudan sonra dinle", "Onay ve açıklama sorularından sonra mikrofonu açar", st.listenAfterQuestion) { b -> a.updateSettings { it.copy(listenAfterQuestion = b) } }
            HoloSliderRow("Sessizlik süresi", "%.1f sn".format(st.silenceTimeoutMs / 1000f), st.silenceTimeoutMs.toFloat(), 500f..5000f) { v -> a.updateSettings { it.copy(silenceTimeoutMs = v.toInt()) } }
            HoloSliderRow("Konuşma hızı", "%.1fx".format(st.speechRate), st.speechRate, 0.6f..1.6f) { v -> a.updateSettings { it.copy(speechRate = v) } }
            HoloSliderRow("Ses tonu", "%.1f".format(st.speechPitch), st.speechPitch, 0.6f..1.6f) { v -> a.updateSettings { it.copy(speechPitch = v) } }
        }

        HoloPanel(title = "UYANDIRMA SÖZCÜĞÜ") {
            HoloSwitchRow("\"${st.assistantName}\" deyince dinle", "Kalıcı bildirimle çalışır; pil tüketir", st.wakeWord) { b -> a.updateSettings { it.copy(wakeWord = b) } }
            HoloSwitchRow("Ekran kapalıyken de dinle", "Varsayılan kapalı (pil)", st.wakeWhileScreenOff) { b -> a.updateSettings { it.copy(wakeWhileScreenOff = b) } }
            BodyText("Sistem konuşma tanıyıcısını döngüyle kullanır; bazı cihazlarda her turda kısa bir ses duyulabilir. Bildirimdeki Durdur ile kapanır.", size = 12)
        }

        HoloPanel(title = "GİZLİLİK") {
            HoloSwitchRow("Konuşma geçmişini sakla", "Yalnızca metin, yalnızca bu cihazda", st.keepHistory) { b -> a.updateSettings { it.copy(keepHistory = b) } }
            HoloButton("GEÇMİŞİ SİL", accent = JarvisColors.danger) { a.clearHistory() }
            Spacer(Modifier.height(8.dp))
            BodyText("Ses kaydı saklanmaz. Parolalar kaydedilmez ve gönderilmez. Mesaj, paylaşım, ödeme ve silme işlemleri her zaman onay ister.", size = 12)
        }
        Spacer(Modifier.height(8.dp))
    }
}
