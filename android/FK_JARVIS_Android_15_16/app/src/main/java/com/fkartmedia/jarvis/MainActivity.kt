package com.fkartmedia.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF101F33)
private val Cyan = Color(0xFF38BDF8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisApp() }
    }
}

@Composable
fun JarvisApp() {
    var prompt by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("Claude") }
    var status by remember { mutableStateOf("Masaüstü ajanına bağlanılmadı") }
    var messages by remember { mutableStateOf(listOf("FK JARVIS mobil merkezi hazır.", "Sunucu bağlantısı yapılandırıldığında görevlerini buradan gönderebilirsin.")) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Color(0xFF0B1C31)))).padding(18.dp)) {
            Text("FK JARVIS", color = Color.White, fontSize = 27.sp)
            Text("MOBILE COMMAND CENTER", color = Cyan, fontSize = 11.sp)
            Spacer(Modifier.height(18.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("HOLOGRAPHIC ASSISTANT", color = Cyan, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                        Text("◉", color = Cyan, fontSize = 76.sp)
                    }
                    Text(status, color = Color.LightGray, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Claude", "Gemini Ses").forEach { item ->
                    FilterChip(selected = provider == item, onClick = { provider = item }, label = { Text(item) })
                }
            }
            Text("Görev ve sohbet", color = Color.White, fontSize = 18.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Text(msg, Modifier.padding(12.dp), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("JARVIS'e görev ver") }, minLines = 2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (prompt.isNotBlank()) {
                        messages = messages + "[$provider] Taslak görev: $prompt" + "Görev sunucuya gönderilmek üzere hazır. API bağlantısı henüz yapılandırılmadı."
                        prompt = ""
                    }
                }, modifier = Modifier.weight(1f)) { Text("Görev gönder") }
                OutlinedButton(onClick = { status = "Sesli komut modülü bağlantı bekliyor" }) { Text("🎙") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Görevler • Onaylar • Dosyalar • Ayarlar", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
