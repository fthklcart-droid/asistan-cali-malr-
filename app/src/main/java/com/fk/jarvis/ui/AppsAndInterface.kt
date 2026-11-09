package com.fk.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk.jarvis.apps.AppMatcher
import com.fk.jarvis.core.TextNormalizer

// ---------------------------------------------------------------- UYGULAMA

/** UYGULAMA: cihazdaki başlatılabilir uygulamalar; arama, tek dokunuşla açma ve sesli komut ipucu. */
@Composable
fun AppsScreen(s: HoloState, a: HoloActions) {
    var query by remember { mutableStateOf("") }
    val all = s.installedApps
    val list = remember(all, query) {
        if (query.isBlank()) all
        else {
            val q = TextNormalizer.fold(query)
            all.filter { TextNormalizer.fold(it.label).contains(q) || it.packageName.contains(query.trim(), ignoreCase = true) }
        }
    }
    val featured = remember(all) {
        listOf("youtube", "whatsapp", "chrome", "kamera", "galeri", "haritalar", "capcut", "lightroom", "photoshop", "instagram", "spotify")
            .mapNotNull { (AppMatcher.match(it, all) as? com.fk.jarvis.apps.AppMatch.Found)?.app }
            .distinctBy { it.packageName }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenTitle("UYGULAMA", if (all.isEmpty()) "Uygulamalar yükleniyor…" else "${all.size} uygulama · dokun ya da sesle aç")
        Spacer(Modifier.height(10.dp))
        HoloField(query, { query = it }, placeholder = "Uygulama ara…")
        Spacer(Modifier.height(10.dp))
        if (featured.isNotEmpty() && query.isBlank()) {
            FieldLabel("HIZLI ERİŞİM")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                featured.forEach { app -> HoloChip(app.label.uppercase(), active = true) { a.launchApp(app) } }
            }
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.packageName }) { app -> AppTile(app.label) { a.launchApp(app) } }
        }
    }
}

@Composable
private fun AppTile(label: String, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val accent = JarvisColors.violet
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Color(0x88000A12))))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.05f))))
                .border(1.dp, accent.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(2).uppercase(), color = Color.White, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = JarvisColors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- ARAYÜZ

/** ARAYÜZ: avatar, vurgu rengi, holografik efektler. */
@Composable
fun InterfaceScreen(s: HoloState, a: HoloActions) {
    val st = s.settings
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("ARAYÜZ", "Avatar, renkler ve holografik efektler")

        HoloPanel(title = "AVATAR") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleCard("ART", "Holografik mavi büst, dağlar", st.avatarStyle != "neon", Color(0xFF46D7FF), Modifier.weight(1f)) {
                    a.updateSettings { it.copy(avatarStyle = "art") }
                }
                StyleCard("NEON", "Mor neon figür, yıldırımlar", st.avatarStyle == "neon", Color(0xFFB05CFF), Modifier.weight(1f)) {
                    a.updateSettings { it.copy(avatarStyle = "neon") }
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("ANİMASYON HIZI")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f to "0.5x", 1f to "1x", 2f to "2x").forEach { (v, l) ->
                    HoloChip(l, active = st.avatarSpeed == v) { a.updateSettings { it.copy(avatarSpeed = v) } }
                }
            }
            HoloSwitchRow("3B holografik katman", "Başın çevresinde dönen 3B halkalar, projektör ışığı, telefon eğimiyle derinlik", st.avatarHolo3d) { b ->
                a.updateSettings { it.copy(avatarHolo3d = b) }
            }
            HoloSwitchRow("Avatar ambiyans sesi", checked = st.avatarSound) { b -> a.updateSettings { it.copy(avatarSound = b) } }
            HoloButton("AÇILIŞ ANİMASYONUNU OYNAT", accent = JarvisColors.dim) { a.replayAvatar() }
        }

        HoloPanel(title = "VURGU RENGİ") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("auto", "OTOMATİK", if (st.avatarStyle == "neon") Color(0xFFB05CFF) else Color(0xFF46D7FF)),
                    Triple("cyan", "MAVİ", Color(0xFF46D7FF)),
                    Triple("violet", "MOR", Color(0xFFB05CFF)),
                    Triple("gold", "ALTIN", Color(0xFFFFC14D)),
                    Triple("green", "YEŞİL", Color(0xFF3DFFB0)),
                ).forEach { (id, label, c) -> Swatch(label, c, st.uiAccent == id) { a.updateSettings { it.copy(uiAccent = id) } } }
            }
            Spacer(Modifier.height(6.dp))
            BodyText("Otomatik: avatar stiline uyar (ART mavi, NEON mor).", size = 12)
        }

        HoloPanel(title = "HOLOGRAFİK EFEKTLER") {
            HoloSwitchRow("Geçiş ve giriş animasyonları", "Bölme geçişinde tarama çizgisi, panellerde belirme ve titreme", st.uiAnimations) { b ->
                a.updateSettings { it.copy(uiAnimations = b) }
            }
            HoloSwitchRow("Izgara ve tarama arka planı", "Avatar dışındaki ekranlarda", st.uiGrid) { b -> a.updateSettings { it.copy(uiGrid = b) } }
            BodyText("Eski ya da yavaş telefonlarda efektleri kapatmak akıcılığı ve pili iyileştirir.", size = 12)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StyleCard(title: String, desc: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = if (selected) 0.30f else 0.08f), Color(0xAA000A12))))
            .border(if (selected) 2.dp else 1.dp, color.copy(alpha = if (selected) 1f else 0.4f), shape)
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp).background(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent))))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (selected) StatusDot(color, pulse = true)
        }
        Text(desc, color = JarvisColors.dim, fontSize = 11.sp)
    }
}

@Composable
private fun Swatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(4.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(color, color.copy(alpha = 0.2f))))
                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else color.copy(alpha = 0.6f), CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) Color.White else JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp)
    }
    Spacer(Modifier.width(2.dp))
}
