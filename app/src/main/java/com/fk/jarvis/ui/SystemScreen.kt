package com.fk.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OK = Color(0xFF3DFFB0)

/** SİSTEM: izinler, servisler ve uygulama bilgisi — hepsi cihazdan okunan gerçek durumlar. */
@Composable
fun SystemScreen(s: HoloState, a: HoloActions) {
    val sys = s.system
    var a11yDisclosure by remember { mutableStateOf(false) }
    if (a11yDisclosure) {
        DisclosureDialog(
            title = "Ekran kontrolü için erişilebilirlik izni",
            text = Disclosures.ACCESSIBILITY,
            confirm = "KABUL EDİYORUM",
            onConfirm = { a11yDisclosure = false; a.openAccessibility() },
            onDismiss = { a11yDisclosure = false },
        )
    }
    val okCount = listOf(sys.micGranted, sys.notificationsGranted, sys.sttAvailable, sys.ttsReady).count { it }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("SİSTEM", "Temel bileşenler: $okCount / 4 hazır") }
            HoloButton("YENİLE") { a.refreshSystem() }
        }

        HoloPanel(title = "İZİNLER") {
            StatusRow("Mikrofon", "Sesli komut için", sys.micGranted, if (!sys.micGranted) "İZİN VER" else null) { a.requestMic() }
            StatusRow("Bildirimler", "Arka plan oturum bildirimi", sys.notificationsGranted, if (!sys.notificationsGranted) "İZİN VER" else null) { a.requestNotifications() }
            StatusRow("Takvim", "Yalnızca \"bugün ne yapmam gerekiyor\" için, salt okuma", sys.calendarGranted, if (!sys.calendarGranted) "İZİN VER" else null, optional = true) { a.requestCalendar() }
            if (sys.screenControlBuild) {
                StatusRow("Ekran kontrolü", "Erişilebilirlik hizmeti — isteğe bağlı", sys.accessibilityOn, if (!sys.accessibilityOn) "AÇ" else "AYARLAR", optional = true) { a11yDisclosure = true }
            } else {
                StatusRow("Ekran kontrolü", "Bu \"basit\" sürümde yok", false, null, optional = true) {}
            }
            Spacer(Modifier.height(6.dp))
            HoloButton("UYGULAMA İZİN AYARLARI", accent = JarvisColors.dim) { a.openAppSettings() }
        }

        HoloPanel(title = "SES SERVİSLERİ") {
            StatusRow("Konuşma tanıma", if (sys.sttAvailable) "Cihazda tanıma hizmeti var" else "Tanıma hizmeti yok — Google uygulamasını yükle", sys.sttAvailable, null) {}
            StatusRow("Türkçe seslendirme", sys.ttsStatus, sys.ttsReady, if (!sys.ttsReady) "AYARLAR" else null) { a.openTtsSettings() }
            val volOk = sys.mediaVolume > 0
            StatusRow(
                "Medya sesi",
                if (sys.mediaVolume < 0) "—" else if (volOk) "Düzey ${sys.mediaVolume} / ${sys.mediaVolumeMax}" else "KAPALI — yanıtlar duyulmaz; telefonun ses tuşuyla aç",
                volOk, "SES TESTİ",
            ) { a.testSpeech() }
            StatusRow("Arka plan oturumu", if (sys.serviceRunning) "Ön plan hizmeti çalışıyor (bildirim görünür)" else "Kapalı", sys.serviceRunning, null, optional = true) {}
        }

        HoloPanel(title = "UYGULAMA", accent = if (sys.testSigned) JarvisColors.gold else JarvisColors.violet) {
            KeyValueRow("Sürüm", "${sys.versionName} (${sys.versionCode})")
            KeyValueRow("Android", sys.androidVersion)
            KeyValueRow("Paket", if (sys.screenControlBuild) "TAM" else "BASİT")
            KeyValueRow("İmza", if (sys.testSigned) "TEST ANAHTARI" else "ÜRETİM ANAHTARI", if (sys.testSigned) JarvisColors.gold else OK)
            Spacer(Modifier.height(6.dp))
            FieldLabel("İMZA SHA-256")
            Text(sys.signatureSha256, color = JarvisColors.text, fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp)
            if (sys.testSigned) {
                Spacer(Modifier.height(6.dp))
                BodyText("Bu kurulum herkese açık test anahtarıyla imzalı. Google Play ve geliştirici doğrulaması için kendi anahtarınla imzalanmış sürümü kur.", JarvisColors.gold, 12)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, ok: Boolean, action: String?, optional: Boolean = false, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (ok) OK else if (optional) JarvisColors.dim else JarvisColors.gold, pulse = false, size = 9)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = JarvisColors.text, fontSize = 14.sp)
            Text(subtitle, color = JarvisColors.dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (action != null) { Spacer(Modifier.width(8.dp)); HoloButton(action, accent = if (ok) JarvisColors.dim else JarvisColors.violet) { onAction() } }
    }
}
