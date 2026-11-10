package com.fk.jarvis.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.apps.AppInfo
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.AssistantUi
import com.fk.jarvis.data.AssistantSettings
import com.fk.jarvis.data.ConversationEntry
import com.fk.jarvis.llm.AIProviderManager
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sistem ekranında gösterilen gerçek cihaz/izin durumları. */
data class SystemStatus(
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val accessibilityOn: Boolean = false,
    val screenControlBuild: Boolean = true,
    val sttAvailable: Boolean = false,
    val ttsStatus: String = "—",
    val ttsReady: Boolean = false,
    val serviceRunning: Boolean = false,
    val versionName: String = "—",
    val versionCode: Long = 0,
    val signatureSha256: String = "—",
    val testSigned: Boolean = true,
    val androidVersion: String = "—",
    /** Medya ses düzeyi (TTS ve canlı mod bu kanaldan çalar). */
    val mediaVolume: Int = -1,
    val mediaVolumeMax: Int = 15,
)

/** Sunucu bağlantı testi sonucu. */
data class ConnectionTest(val providerId: String, val running: Boolean, val ok: Boolean?, val message: String, val ms: Long?)

class HoloState(
    val ui: AssistantUi,
    val settings: AssistantSettings,
    val micActive: Boolean,
    val conversation: Boolean,
    val level: StateFlow<Float>,
    val history: List<ConversationEntry>,
    val system: SystemStatus,
    val providerReady: (String) -> Boolean,
    val keySaved: (String) -> Boolean,
    val connectionTest: ConnectionTest?,
    /** Gemini Live (gerçek zamanlı) oturumu açık mı. */
    val liveActive: Boolean = false,
    val installedApps: List<AppInfo> = emptyList(),
)

interface HoloActions {
    fun mic()
    fun endSession()
    fun send(text: String)
    fun confirm(accepted: Boolean)
    fun chooseApp(app: AppInfo)
    fun cancelChoice()
    fun dismissError()
    fun openAppSettings()
    fun replayAvatar()
    fun updateSettings(t: (AssistantSettings) -> AssistantSettings)
    fun saveKey(providerId: String, key: String?)
    fun clearHistory()
    fun openAccessibility()
    fun refreshSystem()
    fun shareDraft(text: String)
    fun closeDraft()
    fun grantPendingPermission(permission: String)
    fun denyPendingPermission()
    fun testProvider(providerId: String)
    fun requestMic()
    fun requestNotifications()
    fun requestCalendar()
    fun openTtsSettings()
    fun loadApps()
    fun testSpeech()
    fun launchApp(info: AppInfo)
}

private enum class Tab(val label: String, val inBar: Boolean) {
    ASSISTANT("ASİSTAN", true), AGENT("AJAN", true), CALENDAR("TAKVİM", true), WEB("WEB", true), APPS("UYGULAMA", true), MENU("MENÜ", true),
    CHAT("SOHBET", false), MEMORY("HAFIZA", false), UI("ARAYÜZ", false), SERVER("SUNUCU", false), SYSTEM("SİSTEM", false), SETTINGS("AYARLAR", false),
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun HoloApp(avatarView: WebView, s: HoloState, a: HoloActions, x: HoloExtras) {
    var tabName by rememberSaveable { mutableStateOf(Tab.ASSISTANT.name) }
    val tab = Tab.valueOf(tabName)
    // Asistan/ajan bir sayfa açtığında WEB, sesle ajan görevi verildiğinde AJAN bölmesine geçilir.
    LaunchedEffect(Unit) { x.browser.showRequests.collect { tabName = Tab.WEB.name } }
    val agentUi by x.agent.ui.collectAsStateWithLifecycle()
    LaunchedEffect(agentUi.runId) { if (agentUi.runId > 0 && agentUi.fromVoice && agentUi.running) tabName = Tab.AGENT.name }
    // Asistan bir onay ya da seçim isterse soruyu görebilmek için ASİSTAN bölmesine dönülür.
    LaunchedEffect(s.ui.confirmQuestion) { if (s.ui.confirmQuestion != null && tab != Tab.ASSISTANT) tabName = Tab.ASSISTANT.name }
    androidx.activity.compose.BackHandler(enabled = tab != Tab.ASSISTANT) {
        tabName = if (tab.inBar) Tab.ASSISTANT.name else Tab.MENU.name
    }
    // Geçiş yönü: sağdaki bölmeye geçerken içerik sağdan, soldakine geçerken soldan döner.
    var dir by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    LaunchedEffect(tab) {
        if (tab == Tab.SYSTEM || tab == Tab.SERVER) a.refreshSystem()
        if (tab == Tab.APPS) a.loadApps()
    }

    Box(Modifier.fillMaxSize().background(JarvisColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Avatar her zaman yüklü kalır (yeniden açılış animasyonu tekrarlanmasın); diğer sekmeler üstünü örter.
                AssistantScreen(avatarView, s, a)
                // Holografik bölme geçişi: belirme + ölçek + dikey kayma, ardından tarama çizgisi.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val d = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        if (!HoloFx.animations) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(340, delayMillis = 40)) + scaleIn(tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.9f) +
                            androidx.compose.animation.slideInHorizontally(tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { d * it / 5 }) togetherWith
                            (fadeOut(tween(240)) + scaleOut(tween(300), targetScale = 0.92f) +
                                androidx.compose.animation.slideOutHorizontally(tween(320)) { -d * it / 6 })
                    },
                    label = "tab",
                ) { t ->
                    // 3B döner geçiş: içerik Y ekseni etrafında perspektifle dönerek gelir/gider.
                    val rot by transition.animateFloat(
                        transitionSpec = { tween(440, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
                        label = "rot3d",
                    ) { e ->
                        if (!HoloFx.animations) 0f else when (e) {
                            androidx.compose.animation.EnterExitState.PreEnter -> 38f * dir
                            androidx.compose.animation.EnterExitState.Visible -> 0f
                            androidx.compose.animation.EnterExitState.PostExit -> -30f * dir
                        }
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        rotationY = rot
                        cameraDistance = 14f * density
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (dir > 0) 0f else 1f, 0.5f)
                    }) {
                    when (t) {
                        Tab.ASSISTANT -> Box(Modifier.fillMaxSize())
                        Tab.AGENT -> Overlay { AgentScreen(x) }
                        Tab.CALENDAR -> Overlay { CalendarScreen(x) }
                        Tab.WEB -> Overlay { BrowserScreen(x) }
                        Tab.MENU -> Overlay { MenuScreen { id -> dir = 1; tabName = id } }
                        Tab.MEMORY -> Overlay { MemoryScreen(s, a, x) }
                        Tab.CHAT -> Overlay { HistoryScreen(s, a) }
                        Tab.APPS -> Overlay { AppsScreen(s, a) }
                        Tab.UI -> Overlay { InterfaceScreen(s, a) }
                        Tab.SERVER -> Overlay { ServerScreen(s, a) }
                        Tab.SYSTEM -> Overlay { SystemScreen(s, a) }
                        Tab.SETTINGS -> Overlay { SettingsScreen(s, a) }
                    }
                    }
                }
                HoloSweep(tab)
            }
            TabBar(tab) { dir = if (it.ordinal >= tab.ordinal) 1 else -1; tabName = it.name }
        }

        // Çalışma zamanı izni öncesi açık bilgilendirme (Google Play kullanıcı verisi politikası).
        s.ui.permissionRequest?.let { perm ->
            DisclosureDialog(
                title = "Takvim izni",
                text = if (perm == android.Manifest.permission.WRITE_CALENDAR)
                    "Onayladığın etkinliği takvimine yazabilmem için takvim yazma iznine ihtiyacım var. Yalnızca senin onayladığın " +
                        "etkinlikler eklenir; mevcut etkinliklerin değiştirilmez ya da silinmez. Bir sonraki ekranda Android izin isteyecek."
                else "Ajandanı yanıtlayabilmem için takvimindeki etkinlikleri okumam gerekiyor. Takvim yalnızca okunur, değiştirilmez; " +
                    "etkinlik bilgileri telefonundan çıkmaz. Bir sonraki ekranda Android izin isteyecek.",
                confirm = "DEVAM",
                onConfirm = { a.grantPendingPermission(perm) },
                onDismiss = { a.denyPendingPermission() },
            )
        }
        if (!s.settings.consentAccepted) {
            ConsentScreen(onAccept = { a.updateSettings { it.copy(consentAccepted = true) } })
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    // Alttaki asistan ekranına dokunuşların geçmesini engeller.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        HoloBackdrop()
        content()
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xF2000306)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, JarvisColors.violet.copy(alpha = 0.6f), Color.Transparent))))
        // 7 bölmenin hepsi ekrana sığar (kaydırma gerekmez).
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            Tab.entries.filter { it.inBar }.forEach { t ->
                val on = t == selected || (t == Tab.MENU && !selected.inBar)
                // Seçili bölme: yaylı genişleyen gösterge, renk geçişi, hafif büyüme ve alttan parlama.
                val spring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.55f, stiffness = 420f)
                val barW by androidx.compose.animation.core.animateDpAsState(if (on) 30.dp else 6.dp, spring, label = "bar")
                val col by androidx.compose.animation.animateColorAsState(if (on) JarvisColors.violet else JarvisColors.dim, tween(260), label = "col")
                val scale by androidx.compose.animation.core.animateFloatAsState(if (on) 1.08f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 380f), label = "sc")
                val glow by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, tween(320), label = "glow")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, JarvisColors.violet.copy(alpha = 0.16f * glow)))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.width(barW).height(2.dp).background(if (on) Brush.horizontalGradient(listOf(JarvisColors.violet.copy(alpha = 0.3f), JarvisColors.violet, JarvisColors.violet.copy(alpha = 0.3f))) else Brush.horizontalGradient(listOf(JarvisColors.stroke, JarvisColors.stroke))))
                    Spacer(Modifier.height(7.dp))
                    Text(t.label, color = col, fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.sp,
                        maxLines = 1, softWrap = false, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ASİSTAN

private val stateText = mapOf(
    AssistantState.IDLE to "HAZIR",
    AssistantState.LISTENING to "DİNLİYOR",
    AssistantState.PROCESSING to "DÜŞÜNÜYOR",
    AssistantState.SPEAKING to "KONUŞUYOR",
    AssistantState.EXECUTING to "İŞLEM YAPIYOR",
    AssistantState.CONFIRMING to "ONAY BEKLİYOR",
    AssistantState.ERROR to "HATA",
)

fun stateColor(st: AssistantState): Color = when (st) {
    AssistantState.LISTENING -> Color(0xFF7CF3FF)
    AssistantState.SPEAKING -> JarvisColors.gold
    AssistantState.PROCESSING, AssistantState.EXECUTING -> Color(0xFFB98CFF)
    AssistantState.CONFIRMING -> JarvisColors.gold
    AssistantState.ERROR -> JarvisColors.danger
    AssistantState.IDLE -> JarvisColors.violet
}

@Composable
private fun AssistantScreen(avatarView: WebView, s: HoloState, a: HoloActions) {
    val ui = s.ui
    val st = s.settings
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { avatarView }, modifier = Modifier.fillMaxSize())
        // Okunabilirlik için üst/alt karartma
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))))

        // ------------------------------- HUD başlığı
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SRİUS", color = JarvisColors.text, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                    Text("${st.assistantName.uppercase()} · KİŞİSEL ASİSTAN", color = JarvisColors.violet, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                StatusPill(stateText[ui.state] ?: "", stateColor(ui.state), pulse = ui.state != AssistantState.IDLE)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(if (s.micActive) "MİKROFON AÇIK" else "MİKROFON KAPALI", if (s.micActive) JarvisColors.danger else JarvisColors.dim, pulse = s.micActive)
                val pName = AIProviderManager.choices.firstOrNull { it.first == st.provider }?.second ?: "?"
                val ready = st.provider == "none" || s.providerReady(st.provider)
                StatusPill(if (st.provider == "none") "CİHAZ İÇİ" else "YZ · ${pName.uppercase()}", if (ready) Color(0xFF3DFFB0) else JarvisColors.gold)
                if (s.liveActive) StatusPill("CANLI · GEMINI LIVE", Color(0xFFFF4D8D), pulse = true)
                else if (st.liveMode) StatusPill("CANLI MOD HAZIR", Color(0xFFFF4D8D))
                if (s.conversation && !s.liveActive) StatusPill("SESLİ OTURUM", JarvisColors.gold, pulse = true)
                if (st.wakeWord) StatusPill("\"${st.assistantName.uppercase()}\" DİNLENİYOR", Color(0xFF7CF3FF))
            }
            ui.error?.let { err ->
                Spacer(Modifier.height(10.dp))
                HoloPanel(title = "UYARI", accent = JarvisColors.danger) {
                    BodyText(err, JarvisColors.text, 14)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.micDenied) HoloButton("ANDROID AYARLARI", accent = JarvisColors.danger) { a.openAppSettings() }
                        HoloButton("KAPAT", accent = JarvisColors.dim) { a.dismissError() }
                    }
                }
            }
        }

        // ------------------------------- Alt kontrol alanı
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val heard = ui.partialTranscript.ifBlank { ui.transcript }
            if (heard.isNotBlank() || ui.response.isNotBlank()) {
                HoloPanel(modifier = Modifier.heightIn(max = 220.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (heard.isNotBlank()) {
                            FieldLabel("SEN" + if (ui.partialTranscript.isNotBlank()) " · DİNLENİYOR" else "")
                            Text(heard, color = JarvisColors.text, fontSize = 15.sp, lineHeight = 20.sp)
                        }
                        if (ui.response.isNotBlank()) {
                            if (heard.isNotBlank()) Spacer(Modifier.height(8.dp))
                            FieldLabel(st.assistantName.uppercase() + if (ui.lastAnswerFromCloud) " · BULUT" else " · CİHAZ")
                            Text(ui.response, color = JarvisColors.pink, fontSize = 15.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }

            ui.draft?.let { draft ->
                HoloPanel(title = "TASLAK · PANODA", accent = Color(0xFFB98CFF)) {
                    Text(draft, color = JarvisColors.text, fontSize = 14.sp, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoloButton("PAYLAŞ…", accent = JarvisColors.gold) { a.shareDraft(draft) }
                        HoloButton("KAPAT", accent = JarvisColors.dim) { a.closeDraft() }
                    }
                }
            }

            if (ui.confirmQuestion != null && ui.appChoices.isEmpty() && ui.permissionRequest == null) {
                HoloPanel(title = "ONAY GEREKİYOR", accent = JarvisColors.gold) {
                    BodyText(ui.confirmQuestion, JarvisColors.text, 15)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoloButton("ONAYLA", accent = JarvisColors.gold, filled = true) { a.confirm(true) }
                        HoloButton("İPTAL", accent = JarvisColors.dim) { a.confirm(false) }
                    }
                }
            }
            if (ui.appChoices.isNotEmpty()) {
                HoloPanel(title = "HANGİ UYGULAMA?", accent = JarvisColors.gold) {
                    ui.appChoices.forEachIndexed { i, app ->
                        Row(Modifier.fillMaxWidth().clickable { a.chooseApp(app) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = JarvisColors.gold, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                            Text(app.label, color = JarvisColors.text, fontSize = 15.sp)
                        }
                    }
                    HoloButton("İPTAL", accent = JarvisColors.dim) { a.cancelChoice() }
                }
            }

            // Durum şeridi + ses seviyesi
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(stateColor(ui.state), pulse = ui.state != AssistantState.IDLE)
                Spacer(Modifier.width(8.dp))
                Text("DURUM: ${stateText[ui.state]}", color = stateColor(ui.state), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                val lv by s.level.collectAsStateWithLifecycle()
                LevelBars(lv, stateColor(ui.state))
            }

            // Komut satırı + mikrofon küresi
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Komut yaz…", color = JarvisColors.dim, fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { a.send(text); text = "" }),
                        trailingIcon = {
                            Text("GÖNDER", color = JarvisColors.violet, fontFamily = Mono, fontSize = 11.sp,
                                modifier = Modifier.clickable { a.send(text); text = "" }.padding(horizontal = 10.dp, vertical = 8.dp))
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xAA000A12), unfocusedContainerColor = Color(0xAA000A12),
                            focusedBorderColor = JarvisColors.violet, unfocusedBorderColor = JarvisColors.stroke,
                            focusedTextColor = JarvisColors.text, unfocusedTextColor = JarvisColors.text, cursorColor = JarvisColors.violet,
                        ),
                        shape = RoundedCornerShape(4.dp),
                    )
                    if (!s.liveActive && (s.conversation || ui.state == AssistantState.SPEAKING || ui.state == AssistantState.PROCESSING)) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HoloButton(if (ui.state == AssistantState.SPEAKING) "SUSTUR" else "DURDUR", accent = JarvisColors.gold) { a.endSession() }
                            HoloButton("TEKRAR OYNAT", accent = JarvisColors.dim) { a.replayAvatar() }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                val lv by s.level.collectAsStateWithLifecycle()
                val listening = ui.state == AssistantState.LISTENING
                MicOrb(
                    label = when {
                        s.liveActive -> "BİTİR"
                        st.liveMode -> "CANLI"
                        listening -> "TAMAM"
                        ui.state == AssistantState.SPEAKING -> "DİNLE"
                        else -> "KONUŞ"
                    },
                    color = stateColor(ui.state),
                    level = lv,
                    busy = ui.state != AssistantState.IDLE,
                    onClick = { a.mic() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- SOHBET

@Composable
private fun HistoryScreen(s: HoloState, a: HoloActions) {
    val list = s.history
    val listState = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.scrollToItem(list.size - 1) }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("SOHBET", "${list.size} kayıt · yalnızca metin, cihazda saklanır") }
            if (list.isNotEmpty()) HoloButton("TEMİZLE", accent = JarvisColors.danger) { a.clearHistory() }
        }
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            HoloPanel(title = "BOŞ") { BodyText("Henüz konuşma yok. ASİSTAN sekmesinden KONUŞ'a bas ya da bir komut yaz.") }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(list) { e ->
                val mine = e.fromUser
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    val c = if (mine) JarvisColors.text else JarvisColors.pink
                    val accent = if (mine) JarvisColors.stroke else JarvisColors.violet.copy(alpha = 0.6f)
                    Column(
                        Modifier.fillMaxWidth(0.86f)
                            .background(if (mine) Color(0x66001018) else Color(0x8803141C), RoundedCornerShape(6.dp))
                            .border(1.dp, accent, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        FieldLabel((if (mine) "SEN" else s.settings.assistantName.uppercase()) + " · " + fmt.format(Date(e.time)))
                        Spacer(Modifier.height(3.dp))
                        Text(e.text, color = c, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}
