package com.fk.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.apps.CalendarEvent
import com.fk.jarvis.apps.CalendarText
import com.fk.jarvis.data.Routine
import com.fk.jarvis.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TRL = Locale.forLanguageTag("tr-TR")
private val GREEN = Color(0xFF3DFFB0)

/** PLAN: bugünün özeti, görevler, rutinler/hatırlatıcılar ve notlar — hepsi telefonda saklanır. */
@Composable
fun PlanScreen(s: HoloState, a: HoloActions, x: HoloExtras, openAgent: () -> Unit) {
    val tasks by x.tasks.tasks.collectAsStateWithLifecycle()
    val routines by x.routines.routines.collectAsStateWithLifecycle()
    val notes by x.notes.notes.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(x.calendarVersion, x.calendarGranted) {
        events = if (!x.calendarGranted) emptyList() else withContext(Dispatchers.IO) { runCatching { x.calendar.events(today, 1, zone) }.getOrDefault(emptyList()) }
    }
    val open = x.tasks.sorted(tasks.filter { !it.done })
    val dueToday = open.filter { it.due != null && it.due <= today.toString() }
    val doneCount = tasks.count { it.done }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("PLAN", today.format(DateTimeFormatter.ofPattern("d MMMM EEEE", TRL)) + " · görevler, rutinler, notlar")

        // ---------------------------------------------------------------- BUGÜN
        HoloPanel(title = "BUGÜN") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("ETKİNLİK", if (x.calendarGranted) events.size.toString() else "—", JarvisColors.violet, Modifier.weight(1f))
                Stat("BUGÜNKÜ GÖREV", dueToday.size.toString(), if (dueToday.any { it.due!! < today.toString() }) JarvisColors.danger else JarvisColors.gold, Modifier.weight(1f))
                Stat("AÇIK GÖREV", open.size.toString(), GREEN, Modifier.weight(1f))
            }
            val fmt = DateTimeFormatter.ofPattern("HH:mm", TRL)
            val now = Instant.now().toEpochMilli()
            events.filter { it.allDay || it.end > now }.take(4).forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.allDay) "GÜN BOYU" else Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt), color = JarvisColors.violet, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    Text(e.title, color = JarvisColors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!x.calendarGranted) {
                Spacer(Modifier.height(8.dp))
                HoloButton("TAKVİM İZNİ VER", accent = JarvisColors.dim) { x.requestCalendar() }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoloButton("GÜNÜMÜ PLANLA", filled = true) {
                    if (x.agent.start("Bugünkü takvimime ve açık görevlerime bak; en önemli 3 işi belirle ve boş zaman dilimlerine yerleştirilmiş gerçekçi bir gün planı öner. Takvime bir şey yazma.", profileId = "planlayici")) openAgent()
                    else x.toast("Başka bir görev çalışıyor.")
                }
                HoloButton("HAFTAMI PLANLA", accent = JarvisColors.gold) {
                    if (x.agent.start("Önümüzdeki 7 günün takvimine ve açık görevlerime bak; görevleri günlere dağıtan bir hafta planı öner, çakışma ve aşırı yüklü günleri belirt. Takvime bir şey yazma.", profileId = "planlayici")) openAgent()
                    else x.toast("Başka bir görev çalışıyor.")
                }
                HoloButton("SESLİ ÖZET", accent = JarvisColors.dim) {
                    x.speak(
                        (if (x.calendarGranted) CalendarText.describe(events, today, 1, zone, today) + " " else "") +
                            com.fk.jarvis.data.TaskStore.describe(dueToday, today, "Bugünkü görevlerin").replace("Açık görevin yok.", "Bugün için görevin yok.")
                    )
                }
            }
        }

        // ---------------------------------------------------------------- GÖREVLER
        HoloPanel(title = "GÖREVLER", accent = GREEN, trailing = { Text("${open.size} AÇIK", color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp) }) {
            var title by rememberSaveable { mutableStateOf("") }
            var whenSel by rememberSaveable { mutableStateOf(0) }   // 0 tarihsiz, 1 bugün, 2 yarın
            var high by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloField(title, { title = it }, placeholder = "Yeni görev", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HoloButton("EKLE", accent = GREEN, filled = true, enabled = title.isNotBlank()) {
                    val due = when (whenSel) { 1 -> today.toString(); 2 -> today.plusDays(1).toString(); else -> null }
                    val t = x.tasks.add(title, due, null, if (high) 2 else 1)
                    if (t != null) { title = ""; high = false }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TARİHSİZ", "BUGÜN", "YARIN").forEachIndexed { i, l -> HoloChip(l, active = whenSel == i, accent = GREEN) { whenSel = i } }
                HoloChip("ÖNEMLİ", active = high, accent = JarvisColors.danger) { high = !high }
            }
            Spacer(Modifier.height(6.dp))
            if (open.isEmpty()) BodyText("Açık görev yok. Sesle: \"Görev ekle: yarın raporu gönder\".")
            open.take(40).forEach { t -> TaskRow(t, today, onToggle = { x.tasks.setDone(t.id, true) }, onDelete = { x.tasks.remove(t.id) }) }
            if (doneCount > 0) {
                Spacer(Modifier.height(8.dp))
                var showDone by rememberSaveable { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton(if (showDone) "TAMAMLANANLARI GİZLE" else "TAMAMLANAN ($doneCount)", accent = JarvisColors.dim) { showDone = !showDone }
                    if (showDone) HoloButton("TEMİZLE", accent = JarvisColors.danger) { x.tasks.clearDone() }
                }
                AnimatedVisibility(showDone, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        tasks.filter { it.done }.sortedByDescending { it.doneAt }.take(20).forEach { t ->
                            TaskRow(t, today, onToggle = { x.tasks.setDone(t.id, false) }, onDelete = { x.tasks.remove(t.id) })
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- RUTİNLER
        HoloPanel(title = "RUTİNLER VE HATIRLATICILAR", accent = JarvisColors.gold) {
            var showForm by rememberSaveable { mutableStateOf(false) }
            val now = LocalDateTime.now()
            if (routines.isEmpty()) BodyText("Kurulu rutin yok. Sesle: \"Yarın saat 9'da toplantıyı hatırlat\" ya da \"Her gün saat 8'de ilacımı hatırlat\".")
            routines.sortedBy { it.nextFire(now) ?: LocalDateTime.MAX }.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (r.enabled) JarvisColors.gold else JarvisColors.dim, pulse = false, size = 8)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = if (r.enabled) JarvisColors.text else JarvisColors.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val kind = when (r.action) { Routine.Action.REMIND -> "HATIRLAT"; Routine.Action.SAY -> "SÖYLE"; Routine.Action.TASK -> "AJAN GÖREVİ" }
                        Text("$kind · ${r.scheduleText()}" + if (!r.enabled) " · tamamlandı/kapalı" else "", color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp)
                    }
                    if (r.kind != Routine.Kind.ONCE) {
                        HoloButton(if (r.enabled) "AÇIK" else "KAPALI", accent = if (r.enabled) JarvisColors.gold else JarvisColors.dim) { x.routines.setEnabled(r.id, !r.enabled); x.routinesChanged() }
                        Spacer(Modifier.width(6.dp))
                    }
                    HoloButton("✕", accent = JarvisColors.dim) { x.routines.remove(r.id); x.routinesChanged() }
                }
            }
            Spacer(Modifier.height(8.dp))
            HoloButton(if (showForm) "KAPAT" else "+ YENİ RUTİN", accent = JarvisColors.gold) { showForm = !showForm }
            AnimatedVisibility(showForm, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                RoutineForm(today) { r ->
                    val (saved, err) = x.routines.add(r)
                    if (saved != null) { x.routinesChanged(); a.requestNotifications(); showForm = false; x.toast("Kuruldu: ${saved.scheduleText()}") } else x.toast(err ?: "Kurulamadı.")
                }
            }
            Spacer(Modifier.height(10.dp))
            HoloSwitchRow(
                "Sessiz saatler (${s.settings.quietStart}–${s.settings.quietEnd})",
                "Bu aralıkta tekrarlayan rutinler konuşmaz ve bildirimi sessiz gelir. Belirli bir an için kurduğun tek seferlik hatırlatıcılar çalışır.",
                s.settings.quietHours,
            ) { b -> a.updateSettings { it.copy(quietHours = b) } }
            BodyText("Bildirimler Android'in pil yönetimi nedeniyle birkaç dakika gecikebilir. Uygulama kapalıyken rutinler yalnızca bildirim gösterir; seslendirme ve ajan görevi bildirime dokununca başlar.", size = 11)
        }

        // ---------------------------------------------------------------- NOTLAR
        HoloPanel(title = "NOTLAR", accent = JarvisColors.violet, trailing = { Text("${notes.size}", color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp) }) {
            var text by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloField(text, { text = it }, placeholder = "Yeni not (toplantı notu, fikir…)", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HoloButton("KAYDET", filled = true, enabled = text.isNotBlank()) {
                    if (x.notes.add(text) != null) text = "" else x.toast("Gizli bilgi içeren notlar kaydedilmez.")
                }
            }
            if (notes.isEmpty()) { Spacer(Modifier.height(6.dp)); BodyText("Sesle: \"Not al: müşteri cuma günü dönecek\".") }
            notes.sortedByDescending { it.created }.take(30).forEach { n ->
                var open by remember(n.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(n.text, color = JarvisColors.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = if (open) 60 else 3, overflow = TextOverflow.Ellipsis)
                        Text(DateTimeFormatter.ofPattern("d MMM HH:mm", TRL).format(Instant.ofEpochMilli(n.created).atZone(zone)), color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp)
                    }
                    HoloButton("✕", accent = JarvisColors.dim) { x.notes.remove(n.id) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Column(modifier.clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.5f), shape).padding(10.dp)) {
        Text(value, color = color, fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = JarvisColors.dim, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1)
    }
}

@Composable
private fun TaskRow(t: Task, today: LocalDate, onToggle: () -> Unit, onDelete: () -> Unit) {
    val overdue = !t.done && t.due != null && t.due < today.toString()
    val color = if (t.done) JarvisColors.dim else if (overdue) JarvisColors.danger else if (t.priority == 2) JarvisColors.gold else GREEN
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        // Onay kutusu
        Box(
            Modifier.size(22.dp).clip(CutCornerShape(4.dp)).border(1.5.dp, color, CutCornerShape(4.dp))
                .background(if (t.done) color.copy(alpha = 0.35f) else Color.Transparent).clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) { if (t.done) Text("✓", color = JarvisColors.text, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, color = if (t.done) JarvisColors.dim else JarvisColors.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            val meta = listOfNotNull(
                t.due?.let { d -> (if (overdue) "GECİKMİŞ · " else "") + CalendarText.dayLabel(LocalDate.parse(d), today) + (t.time?.let { " $it" } ?: "") },
                if (t.priority == 2) "ÖNEMLİ" else null,
                t.project.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = color, fontFamily = Mono, fontSize = 10.sp)
        }
        HoloButton("✕", accent = JarvisColors.dim) { onDelete() }
    }
}

@Composable
private fun RoutineForm(today: LocalDate, onSave: (Routine) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf(0) }   // 0 bugün/yarın bir kez, 1 her gün, 2 hafta içi, 3 hafta sonu
    var action by rememberSaveable { mutableStateOf(0) }   // 0 hatırlat, 1 söyle, 2 ajan görevi
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HoloField(text, { text = it }, placeholder = if (action == 2) "Ajan görevi (ör. Bugünün teknoloji haberlerini özetle)" else "Ne hatırlatayım / ne söyleyeyim?", modifier = Modifier.fillMaxWidth())
        HoloField(time, { time = it }, placeholder = "Saat (ör. 08:30)", modifier = Modifier.fillMaxWidth())
        FieldLabel("TEKRAR")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("BİR KEZ", "HER GÜN", "HAFTA İÇİ", "HAFTA SONU").forEachIndexed { i, l -> HoloChip(l, active = repeat == i, accent = JarvisColors.gold) { repeat = i } }
        }
        FieldLabel("NE YAPSIN")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("HATIRLAT", "SESLİ SÖYLE", "AJAN GÖREVİ").forEachIndexed { i, l -> HoloChip(l, active = action == i, accent = JarvisColors.gold) { action = i } }
        }
        err?.let { BodyText(it, JarvisColors.danger) }
        HoloButton("RUTİNİ KUR", accent = JarvisColors.gold, filled = true) {
            val t = CalendarText.parseTime(time.trim())
            when {
                text.isBlank() -> err = "Metin gerekli."
                t == null -> err = "Saati 08:30 biçiminde yaz."
                else -> {
                    err = null
                    val hhmm = "%02d:%02d".format(t.hour, t.minute)
                    val kind = when (repeat) { 0 -> Routine.Kind.ONCE; 1 -> Routine.Kind.DAILY; else -> Routine.Kind.WEEKLY }
                    // Bir kez: saat bugün geçtiyse yarın.
                    val date = if (repeat == 0) (if (LocalDateTime.now().toLocalTime() < t) today else today.plusDays(1)).toString() else null
                    val days = when (repeat) { 2 -> setOf(1, 2, 3, 4, 5); 3 -> setOf(6, 7); else -> emptySet() }
                    val act = when (action) { 1 -> Routine.Action.SAY; 2 -> Routine.Action.TASK; else -> Routine.Action.REMIND }
                    onSave(Routine(0, text.trim().take(120), kind, hhmm, date, days, act, text.trim(), created = System.currentTimeMillis()))
                    text = ""; time = ""
                }
            }
        }
    }
}
