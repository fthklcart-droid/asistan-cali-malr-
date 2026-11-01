@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fkartmedia.jarvis

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ tema (docs/TEMA_VE_ARAYUZ_TR.md)
private val Navy = Color(0xFF060B14)
private val Panel = Color(0xFF0D192A)
private val Cyan = Color(0xFF38BDF8)
private val Purple = Color(0xFF8B5CF6)
private val Muted = Color(0xFF91A8C4)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val TextMain = Color(0xFFEAF4FF)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onSpeech: ((String) -> Unit)? = null

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) onSpeech?.invoke(text)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("tr-TR")
                ttsReady = true
            }
        }
        setContent {
            JarvisApp(
                listen = { cb -> startListening(cb) },
                speak = { text -> speak(text) },
                askNotifications = { askNotificationPermission() },
            )
        }
    }

    private fun startListening(cb: (String) -> Unit): Boolean {
        onSpeech = cb
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "FK JARVIS dinliyor…")
        return try {
            speechLauncher.launch(intent); true
        } catch (e: Exception) {
            false
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "bağlantı")
            .replace(Regex("""[*_#>`|~]+"""), " ")
            .take(3500)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun askNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

// ------------------------------------------------------------------ uygulama durumu
private enum class Tab(val label: String, val icon: String) {
    Genel("Genel", "⌂"), Sohbet("Sohbet", "◉"), Gorevler("Görevler", "☰"), Ayarlar("Ayarlar", "⚙")
}

@Composable
fun JarvisApp(listen: ((String) -> Unit) -> Boolean, speak: (String) -> Unit, askNotifications: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Store.prefs(ctx) }

    var tab by remember { mutableStateOf(Tab.Genel) }
    var apiKey by remember { mutableStateOf(SecureStore.get(ctx, "anthropic_api_key")) }
    var model by remember { mutableStateOf(prefs.getString("model", "claude-opus-5-5") ?: "claude-opus-5-5") }
    var effort by remember { mutableStateOf(prefs.getString("effort", "low") ?: "low") }
    var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak", false)) }

    val chat = remember { mutableStateListOf<ChatLine>() }
    var apiHistory by remember { mutableStateOf(JSONArray()) }
    var busy by remember { mutableStateOf(false) }
    val todos = remember { mutableStateListOf<Todo>() }
    var usage by remember { mutableStateOf(Store.usageToday(ctx)) }

    LaunchedEffect(Unit) {
        val (lines, api) = withContext(Dispatchers.IO) { Store.loadChat(ctx) }
        chat.addAll(lines); apiHistory = api
        todos.addAll(withContext(Dispatchers.IO) { Store.loadTodos(ctx) })
        askNotifications()
    }

    fun persistTodos() {
        val copy = todos.toList()
        scope.launch(Dispatchers.IO) { Store.saveTodos(ctx, copy) }
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        tab = Tab.Sohbet
        chat.add(ChatLine("user", q))
        busy = true
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { ClaudeClient(apiKey, model, effort).send(apiHistory, q) }
                apiHistory = reply.messages
                chat.add(ChatLine("assistant", reply.text))
                withContext(Dispatchers.IO) {
                    Store.addUsage(ctx, reply.costUsd)
                    Store.saveChat(ctx, chat.toList(), reply.messages)
                }
                usage = Store.usageToday(ctx)
                if (speakReplies) speak(reply.text)
            } catch (e: ClaudeException) {
                chat.add(ChatLine("error", e.message ?: "Hata"))
            } catch (e: Exception) {
                chat.add(ChatLine("error", "Beklenmeyen hata: ${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Navy, surface = Panel,
        onPrimary = Navy, onSurface = TextMain, onBackground = TextMain)) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B1220)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Text(t.icon, fontSize = 20.sp) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedTextColor = Cyan, selectedIconColor = Cyan,
                                indicatorColor = Color(0xFF12243A), unselectedTextColor = Muted, unselectedIconColor = Muted)
                        )
                    }
                }
            }
        ) { inner ->
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Color(0xFF10172B), Navy))).padding(inner)) {
                when (tab) {
                    Tab.Genel -> OverviewScreen(apiKey.isNotBlank(), busy, usage, todos.count { !it.done }, onAsk = { send(it) },
                        onGoSettings = { tab = Tab.Ayarlar })
                    Tab.Sohbet -> ChatScreen(chat, busy, speakReplies,
                        onSend = { send(it) },
                        onMic = { if (!listen { heard -> send(heard) }) chat.add(ChatLine("error", "Bu telefonda konuşma tanıma bulunamadı (Google uygulamasını kontrol edin).")) },
                        onToggleSpeak = { speakReplies = it; prefs.edit().putBoolean("speak", it).apply() },
                        onNew = {
                            chat.clear(); apiHistory = JSONArray()
                            scope.launch(Dispatchers.IO) { Store.saveChat(ctx, emptyList(), JSONArray()) }
                        })
                    Tab.Gorevler -> TasksScreen(todos,
                        onAdd = { text -> todos.add(0, Todo(System.currentTimeMillis(), text)); persistTodos() },
                        onToggle = { t ->
                            val i = todos.indexOfFirst { it.id == t.id }
                            if (i >= 0) {
                                val nt = t.copy(done = !t.done)
                                todos[i] = nt
                                if (nt.done) Reminders.cancel(ctx, nt) else Reminders.schedule(ctx, nt)
                                persistTodos()
                            }
                        },
                        onDelete = { t -> Reminders.cancel(ctx, t); todos.removeAll { it.id == t.id }; persistTodos() },
                        onRemind = { t, at ->
                            val i = todos.indexOfFirst { it.id == t.id }
                            if (i >= 0) {
                                val nt = t.copy(remindAt = at)
                                todos[i] = nt
                                Reminders.cancel(ctx, t)
                                Reminders.schedule(ctx, nt)
                                persistTodos()
                                askNotifications()
                            }
                        })
                    Tab.Ayarlar -> SettingsScreen(apiKey, model, effort, speakReplies,
                        onSaveKey = { k -> SecureStore.put(ctx, "anthropic_api_key", k); apiKey = k.trim() },
                        onModel = { model = it; prefs.edit().putString("model", it).apply() },
                        onEffort = { effort = it; prefs.edit().putString("effort", it).apply() },
                        onSpeak = { speakReplies = it; prefs.edit().putBoolean("speak", it).apply() },
                        onClearChat = {
                            chat.clear(); apiHistory = JSONArray()
                            scope.launch(Dispatchers.IO) { Store.saveChat(ctx, emptyList(), JSONArray()) }
                        })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ ortak parçalar
@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.border(1.dp, Color(0xFF1C5277), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun Eyebrow(text: String) = Text(text, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

@Composable
private fun Header(subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("FK JARVIS", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Box(Modifier.background(Color(0xFF12352F), RoundedCornerShape(30.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text("● SECURE", color = Color(0xFF6EE7B7), fontSize = 9.sp)
        }
    }
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF29415D),
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Cyan,
    focusedLabelColor = Cyan, unfocusedLabelColor = Muted)

// ------------------------------------------------------------------ Genel
@Composable
private fun OverviewScreen(hasKey: Boolean, busy: Boolean, usage: Pair<Int, Double>, openTodos: Int,
                           onAsk: (String) -> Unit, onGoSettings: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val pulse = rememberInfiniteTransition(label = "core")
    val glow by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greet = when (hour) { in 5..11 -> "Günaydın"; in 12..17 -> "İyi günler"; in 18..22 -> "İyi akşamlar"; else -> "İyi geceler" }
    val coreColor = if (busy) Purple else Cyan

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("MOBILE COMMAND CENTER  •  FK ART MEDYA")
        PanelCard(Modifier.fillMaxWidth()) {
            Eyebrow("HOLOGRAPHIC CORE")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp).alpha(glow)
                    .background(Brush.radialGradient(listOf(Color(0xFF164A70), Color(0xFF111A30), Navy)), CircleShape)
                    .border(2.dp, coreColor, CircleShape), contentAlignment = Alignment.Center) {
                    Text("◉", color = coreColor, fontSize = 60.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("$greet, FK.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (busy) "Düşünüyorum…" else "Bir şey sor ya da görev ekle.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (hasKey) "● Claude hazır" else "● Claude anahtarı gerekli", color = if (hasKey) Green else Amber, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("JARVIS'e sor") }, singleLine = true,
                colors = inputColors(), shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { if (hasKey) { onAsk(q); q = "" } else onGoSettings() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(if (hasKey) "✦ Asistana sor" else "⚙ Önce anahtarı gir")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("CLAUDE", if (hasKey) "Hazır" else "Anahtar yok", if (hasKey) Green else Amber, Modifier.weight(1f))
            Tile("BUGÜN", "${usage.first} istek • $" + String.format(Locale.US, "%.2f", usage.second), Cyan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("GÖREVLER", "$openTodos açık", if (openTodos > 0) Amber else Green, Modifier.weight(1f))
            Tile("BİLGİSAYAR", "Eşleştirme yakında", Muted, Modifier.weight(1f))
        }
        Text("Sohbetleriniz ve görevleriniz yalnız bu telefonda saklanır. Mesajlar yanıt için Anthropic'e (Claude) gönderilir.",
            color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Tile(title: String, value: String, color: Color, modifier: Modifier) {
    PanelCard(modifier) {
        Eyebrow(title)
        Spacer(Modifier.height(4.dp))
        Text("● $value", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ Sohbet
@Composable
private fun ChatScreen(chat: List<ChatLine>, busy: Boolean, speakReplies: Boolean, onSend: (String) -> Unit, onMic: () -> Unit,
                       onToggleSpeak: (Boolean) -> Unit, onNew: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1 + if (busy) 1 else 0) }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sohbet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("🔊", fontSize = 16.sp)
            Switch(checked = speakReplies, onCheckedChange = onToggleSpeak)
            TextButton(onClick = onNew) { Text("Yeni", color = Cyan) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.isEmpty()) item {
                Text("Merhaba! Haber, hava durumu, kur, tarif, çeviri… ne istersen sor. 🎙 ile konuşabilirsin.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            items(chat) { line -> Bubble(line) }
            if (busy) item { Text("JARVIS düşünüyor…", color = Purple, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Mesaj yaz…", color = Muted) },
                colors = inputColors(), shape = RoundedCornerShape(14.dp), maxLines = 4)
            OutlinedButton(onClick = onMic, enabled = !busy, shape = CircleShape) { Text("🎙") }
            Button(onClick = { onSend(text); text = "" }, enabled = !busy && text.isNotBlank(), shape = RoundedCornerShape(12.dp)) { Text("➤") }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    val mine = line.role == "user"
    val bg = when (line.role) { "user" -> Color(0xFF123B5A); "error" -> Color(0xFF3B1414); else -> Panel }
    val fg = if (line.role == "error") Red else Color.White
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(0.88f).background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, if (mine) Color(0xFF1C5277) else Color(0xFF1B3555), RoundedCornerShape(14.dp)).padding(12.dp)) {
            SelectionContainer { Text(line.text, color = fg, fontSize = 14.sp) }
        }
    }
}

// ------------------------------------------------------------------ Görevler
@Composable
private fun TasksScreen(todos: List<Todo>, onAdd: (String) -> Unit, onToggle: (Todo) -> Unit, onDelete: (Todo) -> Unit,
                        onRemind: (Todo, Long) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var remindFor by remember { mutableStateOf<Todo?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("tr-TR")) }

    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
        Text("Görevler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Yeni görev…", color = Muted) },
                singleLine = true, colors = inputColors(), shape = RoundedCornerShape(14.dp))
            Button(onClick = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }, shape = RoundedCornerShape(12.dp)) { Text("Ekle") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (todos.isEmpty()) item { Text("Henüz görev yok.", color = Muted, modifier = Modifier.padding(8.dp)) }
            items(todos, key = { it.id }) { t ->
                PanelCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t.done, onCheckedChange = { onToggle(t) })
                        Column(Modifier.weight(1f)) {
                            Text(t.text, color = if (t.done) Muted else Color.White, fontSize = 14.sp)
                            t.remindAt?.let { Text("⏰ " + fmt.format(Date(it)), color = Amber, fontSize = 11.sp) }
                        }
                        TextButton(onClick = { remindFor = t }, enabled = !t.done) { Text("⏰") }
                        TextButton(onClick = { onDelete(t) }) { Text("🗑") }
                    }
                }
            }
        }
    }

    remindFor?.let { t ->
        AlertDialog(
            onDismissRequest = { remindFor = null },
            containerColor = Panel,
            title = { Text("Hatırlatma", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.text, color = Muted, fontSize = 12.sp)
                    listOf("15 dakika sonra" to 15L, "1 saat sonra" to 60L, "3 saat sonra" to 180L).forEach { (label, min) ->
                        OutlinedButton(onClick = { onRemind(t, System.currentTimeMillis() + min * 60_000); remindFor = null },
                            modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                    OutlinedButton(onClick = {
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
                        onRemind(t, c.timeInMillis); remindFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Yarın 09:00") }
                    OutlinedButton(onClick = {
                        val now = Calendar.getInstance()
                        TimePickerDialog(ctx, { _, h, m ->
                            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }
                            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                            onRemind(t, c.timeInMillis)
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                        remindFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Saat seç…") }
                }
            },
            confirmButton = { TextButton(onClick = { remindFor = null }) { Text("Kapat", color = Cyan) } }
        )
    }
}

// ------------------------------------------------------------------ Ayarlar
@Composable
private fun SettingsScreen(apiKey: String, model: String, effort: String, speak: Boolean, onSaveKey: (String) -> Unit,
                           onModel: (String) -> Unit, onEffort: (String) -> Unit, onSpeak: (Boolean) -> Unit, onClearChat: () -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ayarlar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        PanelCard(Modifier.fillMaxWidth()) {
            Eyebrow("CLAUDE API ANAHTARI")
            Text(if (apiKey.isBlank()) "Girilmedi. console.anthropic.com adresinden alabilirsiniz."
                 else "Kayıtlı: …" + apiKey.takeLast(4) + " (telefonda şifreli saklanır)", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(keyInput, { keyInput = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("sk-ant-…") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = inputColors())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSaveKey(keyInput); keyInput = ""; saved = true }, enabled = keyInput.isNotBlank()) { Text("Kaydet") }
                OutlinedButton(onClick = { onSaveKey(""); saved = false }, enabled = apiKey.isNotBlank()) { Text("Sil") }
            }
            if (saved) Text("✔ Kaydedildi", color = Green, fontSize = 12.sp)
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Eyebrow("MODEL")
            Pricing.models.forEach { (id, label) ->
                FilterChip(selected = model == id, onClick = { onModel(id) }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF123B5A), selectedLabelColor = Cyan))
            }
            Spacer(Modifier.height(6.dp))
            Eyebrow("DÜŞÜNME DERİNLİĞİ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("low" to "Hızlı", "medium" to "Dengeli", "high" to "Derin").forEach { (id, label) ->
                    FilterChip(selected = effort == id, onClick = { onEffort(id) }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF123B5A), selectedLabelColor = Cyan))
                }
            }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("SESLİ YANIT")
                    Text("Yanıtları Türkçe sesle oku", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = speak, onCheckedChange = onSpeak)
            }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Eyebrow("VERİ")
            Text("Sohbet geçmişi ve görevler yalnız bu telefondadır.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onClearChat, colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)) { Text("Sohbet geçmişini sil") }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Eyebrow("BİLGİSAYAR BAĞLANTISI")
            Text("FK JARVIS masaüstüyle eşleştirme (onay verme, görev gönderme) sonraki sürümde gelecek. " +
                 "Güvenlik için bilgisayardaki köprü şu an yalnız o bilgisayardan erişilebilir.", color = Muted, fontSize = 12.sp)
        }
        Text("FK JARVIS Mobil 0.3.0 • Android 15/16", color = Muted, fontSize = 10.sp)
    }
}
