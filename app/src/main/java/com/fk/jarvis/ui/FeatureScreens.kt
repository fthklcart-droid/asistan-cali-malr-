package com.fk.jarvis.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.agent.AgentController
import com.fk.jarvis.agent.AgentStep
import com.fk.jarvis.apps.CalendarEvent
import com.fk.jarvis.apps.CalendarReader
import com.fk.jarvis.apps.CalendarText
import com.fk.jarvis.apps.NewEvent
import com.fk.jarvis.browser.BrowserController
import com.fk.jarvis.core.IntentClassifier
import com.fk.jarvis.data.MemoryStore
import com.fk.jarvis.data.TopicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TR = Locale.forLanguageTag("tr-TR")
private val OK = Color(0xFF3DFFB0)

/** Yeni bölmelerin (ajan, takvim, tarayıcı, hafıza) ihtiyaç duyduğu denetleyiciler ve eylemler. */
class HoloExtras(
    val agent: AgentController,
    val browser: BrowserController,
    val browserView: WebView,
    val calendar: CalendarReader,
    val memory: MemoryStore,
    val topics: TopicStore,
    val tasks: com.fk.jarvis.data.TaskStore,
    val notes: com.fk.jarvis.data.NoteStore,
    val routines: com.fk.jarvis.data.RoutineStore,
    /** Rutin listesi değişti: alarm yeniden kurulur. */
    val routinesChanged: () -> Unit,
    val calendarGranted: Boolean,
    /** Takvim listesini yenilemek için artan sayaç (etkinlik eklenince). */
    val calendarVersion: Int,
    val pageSummary: String?,
    val pageSummaryBusy: Boolean,
    val undoLabels: List<String>,
    val requestCalendar: () -> Unit,
    val addEvent: (NewEvent) -> Unit,
    val openEvent: (Long) -> Unit,
    val summarizePage: () -> Unit,
    val closeSummary: () -> Unit,
    val openExternal: (String) -> Unit,
    val checkTopicsNow: () -> Unit,
    val undo: () -> Unit,
    val speak: (String) -> Unit,
    val toast: (String) -> Unit,
)

// ===================================================================== AJAN

/** Hazır görevler: (etiket, hedef, profil kimliği; null = otomatik). Hedefte köşeli parantezli yer varsa görev alanına yazılır, kullanıcı tamamlar. */
private val PRESETS: List<Pair<String, List<Triple<String, String, String?>>>> = listOf(
    "KİŞİSEL" to listOf(
        Triple("Günlük brifing", IntentClassifier.BRIEFING_GOAL, "genel"),
        Triple("Yarınki programım", "Yarınki takvim etkinliklerime ve yarına tarihli görevlerime bak, kısaca özetle; çakışma ya da boşluk varsa söyle.", "planlayici"),
        Triple("Telefon kontrolü", "Telefonun pil, sıcaklık, bellek ve depolama durumunu kontrol et ve kısa öneriler ver.", "genel"),
        Triple("Hava + takvim", "Yarın için bulunduğum şehrin hava durumunu araştır (şehri hafızadan öğren; yoksa İstanbul); yarın dışarıda bir etkinliğim varsa hava durumuna göre öneri ver.", "genel"),
    ),
    "PLANLAMA" to listOf(
        Triple("Günümü planla", "Bugünkü takvimime ve açık görevlerime bak; en önemli 3 işi belirle ve boş zaman dilimlerine yerleştirilmiş gerçekçi bir gün planı öner. Takvime bir şey yazma.", "planlayici"),
        Triple("Haftamı planla", "Önümüzdeki 7 günün takvimine ve açık görevlerime bak; görevleri günlere dağıtan bir hafta planı öner. Takvime bir şey yazma.", "planlayici"),
        Triple("Boş akşamlar", "Bu haftaki takvimime bak ve 18:00'den sonra boş olan akşamları listele.", "planlayici"),
        Triple("Görevleri önceliklendir", "Açık görevlerimi oku; aciliyet ve öneme göre sırala, bugün yapılması gereken 3 tanesini gerekçesiyle söyle.", "planlayici"),
    ),
    "İŞ" to listOf(
        Triple("E-posta taslağı", "Şu konuda profesyonel ve kısa bir e-posta taslağı hazırla: [konu ve alıcı]", "is"),
        Triple("Toplantı hazırlığı", "Bugünkü ve yarınki toplantılarıma bak; her biri için ilgili notlarımı ara ve kısa bir hazırlık listesi (gündem, sorulacak sorular) çıkar.", "is"),
        Triple("Teklif taslağı", "Şu iş için müşteriye gönderilecek kısa bir teklif taslağı hazırla: [iş, kapsam, fiyat]", "is"),
        Triple("Haftalık rapor", "Bu hafta tamamlanan ve açık kalan görevlerime ve notlarıma bak; yöneticiye gönderilecek kısa bir haftalık durum raporu taslağı hazırla.", "is"),
        Triple("Şirket araştır", "Şu şirketi araştır; ne iş yaptığını, büyüklüğünü ve son haberleri kaynak adlarıyla özetle: [şirket adı]", "arastirmaci"),
    ),
    "ARAŞTIRMA" to listOf(
        Triple("Derin araştırma", "Şu konuyu derin araştır, kaynakları oku, alıntıları doğrula ve kaynaklı kısa rapor yaz: [konu]", "arastirmaci"),
        Triple("Karşılaştır", "Şunları karşılaştır; artı/eksi ve fiyat bilgilerini kaynak adlarıyla ver: [A ve B]", "arastirmaci"),
        Triple("Açık sayfayı özetle", "Tarayıcıda açık olan sayfayı oku ve en önemli noktaları 4 cümlede özetle.", "arastirmaci"),
    ),
)

@Composable
fun AgentScreen(x: HoloExtras) {
    val ui by x.agent.ui.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf("") }
    var profileId by rememberSaveable { mutableStateOf("") }   // "" = otomatik
    var category by rememberSaveable { mutableStateOf(0) }
    val reports by x.agent.history.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("AJAN", "Çok adımlı görevler: araştırır, tarayıcıda gezer, takvimini okur ve yazar") }
            if (ui.running) StatusPill("ÇALIŞIYOR", JarvisColors.violet, pulse = true)
        }

        HoloPanel(title = "GÖREV") {
            HoloField(goal, { goal = it }, placeholder = "Örn: Cumartesi için sinema seanslarını araştır ve takvimime ekle", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoloButton("BAŞLAT", filled = true, enabled = !ui.running && goal.isNotBlank()) {
                    if (goal.contains('[')) x.toast("Köşeli parantezli yeri kendi bilginle değiştir.")
                    else if (x.agent.start(goal, profileId = profileId.ifBlank { null })) goal = "" else x.toast("Başka bir görev çalışıyor.")
                }
                if (ui.running) HoloButton("DURDUR", accent = JarvisColors.danger) { x.agent.stop() }
                else if (ui.steps.isNotEmpty() || ui.answer != null || ui.error != null) HoloButton("TEMİZLE", accent = JarvisColors.dim) { x.agent.clear() }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("AJAN PROFİLİ")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoloChip("OTOMATİK", active = profileId.isBlank()) { profileId = "" }
                com.fk.jarvis.agent.AgentProfiles.all.forEach { p -> HoloChip(p.label, active = profileId == p.id) { profileId = p.id } }
            }
            Spacer(Modifier.height(4.dp))
            BodyText(
                com.fk.jarvis.agent.AgentProfiles.all.firstOrNull { it.id == profileId }?.let { "${it.description} · en fazla ${it.maxSteps} adım" }
                    ?: "Göreve göre uygun profil seçilir (araştırma, planlama, iş, genel).", size = 12,
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("HAZIR GÖREVLER")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEachIndexed { i, (cat, _) -> HoloChip(cat, active = category == i, accent = JarvisColors.gold) { category = i } }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS[category.coerceIn(0, PRESETS.size - 1)].second.forEach { (label, g, pid) ->
                    HoloChip(label, active = false) {
                        // Doldurulacak yeri olan görevler alana yazılır; diğerleri hemen başlar.
                        if (g.contains('[')) { goal = g; profileId = pid ?: "" }
                        else if (!ui.running) { if (!x.agent.start(g, profileId = pid)) x.toast("Başka bir görev çalışıyor.") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BodyText("Sesle de verebilirsin: \"Ajan, ...\" ya da \"Günaydın\". Takvime yazma, mesaj ve paylaşım gibi işlemler senin onayın olmadan yapılmaz.", size = 12)
        }

        AnimatedVisibility(ui.confirmQuestion != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            HoloPanel(title = "ONAY GEREKİYOR", accent = JarvisColors.gold) {
                BodyText(ui.confirmQuestion.orEmpty(), JarvisColors.text, 15)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton("ONAYLA", accent = OK, filled = true) { x.agent.answer(true) }
                    HoloButton("REDDET", accent = JarvisColors.danger) { x.agent.answer(false) }
                }
            }
        }

        if (ui.goal.isNotBlank()) {
            HoloPanel(title = "ADIMLAR" + if (ui.profile.isNotBlank()) " · ${ui.profile}" else "", trailing = { if (ui.running) ScanLine() }) {
                BodyText("Hedef: ${ui.goal.take(240)}", JarvisColors.text, 13)
                Spacer(Modifier.height(8.dp))
                if (ui.plan.isNotEmpty()) {
                    FieldLabel("PLAN")
                    ui.plan.forEachIndexed { i, p -> Text("${i + 1}. $p", color = JarvisColors.pink, fontSize = 13.sp, lineHeight = 18.sp) }
                    Spacer(Modifier.height(8.dp))
                }
                if (ui.steps.isEmpty() && ui.running) BodyText("Plan yapılıyor…")
                ui.steps.forEach { StepRow(it) }
            }
        }

        ui.answer?.let { ans ->
            HoloPanel(title = "SONUÇ", accent = OK) {
                BodyText(ans, JarvisColors.text, 15)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton("SESLİ OKU") { x.speak(ans) }
                    if (ui.finishedAt > ui.startedAt) BodyText("${(ui.finishedAt - ui.startedAt) / 1000} sn · ${ui.steps.size} adım", size = 12)
                }
            }
        }
        ui.error?.let { HoloPanel(title = "DURUM", accent = JarvisColors.danger) { BodyText(it, JarvisColors.text, 14) } }

        if (reports.isNotEmpty()) {
            HoloPanel(title = "RAPORLAR", accent = JarvisColors.dim, trailing = { HoloButton("SİL", accent = JarvisColors.dim) { x.agent.clearHistory() } }) {
                reports.take(15).forEach { r ->
                    var open by remember(r.at) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (r.ok) OK else JarvisColors.danger, pulse = false, size = 7)
                            Spacer(Modifier.width(8.dp))
                            Text(r.goal, color = JarvisColors.text, fontSize = 13.sp, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Text(
                            "${r.profile} · ${r.steps} adım · " + DateTimeFormatter.ofPattern("d MMM HH:mm", TR).format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())),
                            color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp,
                        )
                        if (open) {
                            Spacer(Modifier.height(4.dp))
                            BodyText(r.text, JarvisColors.text, 13)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HoloButton("SESLİ OKU") { x.speak(r.text) }
                                HoloButton("NOTLARA KAYDET", accent = JarvisColors.dim) {
                                    if (x.notes.add("${r.goal.take(120)}\n${r.text}") != null) x.toast("Notlara kaydedildi.") else x.toast("Kaydedilemedi.")
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepRow(s: AgentStep) {
    val color = when (s.status) {
        AgentStep.Status.RUNNING -> JarvisColors.violet
        AgentStep.Status.WAITING_CONFIRM -> JarvisColors.gold
        AgentStep.Status.DONE -> OK
        AgentStep.Status.FAILED -> JarvisColors.danger
        AgentStep.Status.DECLINED -> JarvisColors.dim
    }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
        Column(Modifier.width(18.dp).padding(top = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusDot(color, pulse = s.status == AgentStep.Status.RUNNING || s.status == AgentStep.Status.WAITING_CONFIRM, size = 9)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("ADIM ${s.index} · ${s.label.uppercase(TR)}", color = color, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            if (s.say.isNotBlank()) Text(s.say, color = JarvisColors.text, fontSize = 13.sp, lineHeight = 18.sp)
            if (s.argsText.isNotBlank()) Text(s.argsText, color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp, maxLines = if (open) 8 else 1, overflow = TextOverflow.Ellipsis)
            s.observation?.let {
                Text(it, color = JarvisColors.dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = if (open) 30 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ScanLine() {
    val t = rememberInfiniteTransition(label = "scan")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.width(60.dp).height(2.dp).background(JarvisColors.stroke)) {
        Box(Modifier.fillMaxHeight().width(18.dp).padding(start = (42 * p).dp).background(JarvisColors.violet))
    }
}

// ===================================================================== TAKVİM

@Composable
fun CalendarScreen(x: HoloExtras) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var selectedIso by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = LocalDate.parse(selectedIso)
    val weekStart = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showForm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(weekStart, x.calendarVersion, x.calendarGranted) {
        if (!x.calendarGranted) { events = emptyList(); return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) { runCatching { x.calendar.events(weekStart, 7, zone) } }
        events = r.getOrDefault(emptyList()); loadError = r.exceptionOrNull()?.let { "Takvim okunamadı." }
    }
    fun dayOf(e: CalendarEvent): LocalDate = Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) ZoneId.of("UTC") else zone).toLocalDate()
    val dayEvents = events.filter { dayOf(it) == selected }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ScreenTitle("TAKVİM", selected.format(DateTimeFormatter.ofPattern("MMMM yyyy", TR)).replaceFirstChar { it.titlecase(TR) })
            }
            HoloButton("◀", accent = JarvisColors.dim) { selectedIso = selected.minusWeeks(1).toString() }
            Spacer(Modifier.width(6.dp))
            HoloButton("BUGÜN", accent = JarvisColors.dim) { selectedIso = today.toString() }
            Spacer(Modifier.width(6.dp))
            HoloButton("▶", accent = JarvisColors.dim) { selectedIso = selected.plusWeeks(1).toString() }
        }

        // Hafta şeridi
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 7) {
                val d = weekStart.plusDays(i.toLong())
                val isSel = d == selected
                val has = events.any { dayOf(it) == d }
                val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(if (isSel) Brush.verticalGradient(listOf(JarvisColors.violet.copy(alpha = 0.35f), JarvisColors.violet.copy(alpha = 0.08f))) else Brush.verticalGradient(listOf(Color(0x33000A10), Color(0x33000A10))))
                        .border(1.dp, if (isSel) JarvisColors.violet else if (d == today) JarvisColors.gold.copy(alpha = 0.7f) else JarvisColors.stroke, shape)
                        .clickable { selectedIso = d.toString() }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE", TR)).uppercase(TR).take(3), color = JarvisColors.dim, fontFamily = Mono, fontSize = 9.sp)
                    Text(d.dayOfMonth.toString(), color = if (isSel) JarvisColors.text else JarvisColors.text.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    Box(Modifier.size(5.dp).clip(CutCornerShape(2.dp)).background(if (has) JarvisColors.violet else Color.Transparent))
                }
            }
        }

        if (!x.calendarGranted) {
            HoloPanel(title = "İZİN", accent = JarvisColors.gold) {
                BodyText("Etkinliklerini göstermek için takvim iznine ihtiyacım var. Takvim bilgilerin telefonda kalır; yalnızca sen sorduğunda ya da ajan takvim aracını kullandığında okunur.", JarvisColors.text, 13)
                Spacer(Modifier.height(10.dp))
                HoloButton("İZİN VER", filled = true) { x.requestCalendar() }
            }
        } else {
            HoloPanel(title = CalendarText.dayLabel(selected, today).uppercase(TR), trailing = { Text("${dayEvents.size} ETKİNLİK", color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp) }) {
                loadError?.let { BodyText(it, JarvisColors.danger) }
                if (dayEvents.isEmpty()) BodyText("Bu gün için kayıtlı etkinlik yok.")
                dayEvents.forEach { e -> EventRow(e, zone) { x.openEvent(e.id) } }
            }
        }

        HoloPanel(title = "YENİ ETKİNLİK", accent = OK, trailing = { HoloButton(if (showForm) "KAPAT" else "+ EKLE", accent = OK) { showForm = !showForm } }) {
            AnimatedVisibility(showForm, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                EventForm(selected, today) { ev -> x.addEvent(ev); showForm = false }
            }
            if (!showForm) BodyText("Seçili güne (${CalendarText.dayLabel(selected, today)}) etkinlik ekle. Sesle de olur: \"Yarın saat 3'te diş hekimi randevumu takvime ekle\".", size = 12)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EventRow(e: CalendarEvent, zone: ZoneId, onClick: () -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm", TR)
    val start = Instant.ofEpochMilli(e.begin).atZone(zone)
    val end = Instant.ofEpochMilli(e.end).atZone(zone)
    val c = e.color?.let { Color(it).copy(alpha = 1f) } ?: JarvisColors.violet
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(38.dp).background(c))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(56.dp)) {
            if (e.allDay) Text("GÜN BOYU", color = JarvisColors.dim, fontFamily = Mono, fontSize = 9.sp)
            else {
                Text(start.format(fmt), color = JarvisColors.text, fontFamily = Mono, fontSize = 13.sp)
                Text(end.format(fmt), color = JarvisColors.dim, fontFamily = Mono, fontSize = 11.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(e.title, color = JarvisColors.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(e.location?.takeIf { it.isNotBlank() }, e.calendarName).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = JarvisColors.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EventForm(date: LocalDate, today: LocalDate, onSave: (NewEvent) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf(60) }
    var location by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText("Tarih: ${CalendarText.dayLabel(date, today)} (${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", TR))})", JarvisColors.text, 13)
        HoloField(title, { title = it }, placeholder = "Başlık", modifier = Modifier.fillMaxWidth())
        HoloField(time, { time = it }, placeholder = "Saat (ör. 14:30) — tüm gün için boş bırak", modifier = Modifier.fillMaxWidth())
        FieldLabel("SÜRE")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 120).forEach { m -> HoloChip("$m dk", active = duration == m) { duration = m } }
        }
        HoloField(location, { location = it }, placeholder = "Yer (isteğe bağlı)", modifier = Modifier.fillMaxWidth())
        err?.let { BodyText(it, JarvisColors.danger) }
        HoloButton("TAKVİME KAYDET", accent = OK, filled = true) {
            val t = time.trim()
            val parsed = if (t.isEmpty()) null else CalendarText.parseTime(t)
            when {
                title.isBlank() -> err = "Başlık gerekli."
                t.isNotEmpty() && parsed == null -> err = "Saati 14:30 biçiminde yaz."
                else -> {
                    err = null
                    onSave(NewEvent(title.trim().take(200), date, parsed, duration, location.trim().ifBlank { null }))
                    title = ""; time = ""; location = ""
                }
            }
        }
    }
}

// ===================================================================== WEB

@Composable
fun BrowserScreen(x: HoloExtras) {
    val st by x.browser.state.collectAsStateWithLifecycle()
    var addr by remember(st.url) { mutableStateOf(st.url) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HoloButton("◀", accent = if (st.canGoBack) JarvisColors.violet else JarvisColors.dim, enabled = st.canGoBack) { x.browser.back() }
            Spacer(Modifier.width(4.dp))
            HoloButton("▶", accent = if (st.canGoForward) JarvisColors.violet else JarvisColors.dim, enabled = st.canGoForward) { x.browser.forward() }
            Spacer(Modifier.width(6.dp))
            HoloField(addr, { addr = it }, placeholder = "Adres ya da arama", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            HoloButton("GİT", filled = true) { x.browser.go(addr) }
        }
        // Yükleme çubuğu
        Box(Modifier.fillMaxWidth().height(2.dp).background(JarvisColors.stroke.copy(alpha = 0.3f))) {
            if (st.loading) Box(Modifier.fillMaxHeight().fillMaxWidth(st.progress.coerceIn(5, 100) / 100f).background(Brush.horizontalGradient(listOf(JarvisColors.violet.copy(alpha = 0.3f), JarvisColors.violet))))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (st.url.isEmpty()) JarvisColors.dim else if (st.secure) OK else JarvisColors.gold, pulse = st.loading, size = 7)
            Spacer(Modifier.width(6.dp))
            Text(
                (if (st.url.isEmpty()) "" else if (st.secure) "GÜVENLİ · " else "ŞİFRESİZ · ") + st.title.ifBlank { st.url },
                color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).border(1.dp, JarvisColors.stroke)) {
            AndroidView(
                factory = { (x.browserView.parent as? ViewGroup)?.removeView(x.browserView); x.browserView },
                modifier = Modifier.fillMaxSize(),
            )
            st.error?.let {
                Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { HoloPanel(title = "HATA", accent = JarvisColors.danger) { BodyText(it, JarvisColors.text) } }
            }
            if (x.pageSummary != null || x.pageSummaryBusy) {
                Box(Modifier.align(Alignment.BottomCenter).padding(10.dp).heightIn(max = 340.dp)) {
                    HoloPanel(title = "SAYFA ÖZETİ", accent = OK, trailing = { HoloButton("✕", accent = JarvisColors.dim) { x.closeSummary() } }) {
                        if (x.pageSummaryBusy) BodyText("Sayfa okunuyor ve özetleniyor…")
                        x.pageSummary?.let { s ->
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                BodyText(s, JarvisColors.text, 14)
                                Spacer(Modifier.height(8.dp))
                                HoloButton("SESLİ OKU") { x.speak(s) }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoloButton("ÖZETLE", accent = OK) { x.summarizePage() }
            HoloButton(if (st.loading) "DURDUR" else "YENİLE", accent = JarvisColors.dim) { if (st.loading) x.browser.stop() else x.browser.reload() }
            HoloButton("ANA SAYFA", accent = JarvisColors.dim) { x.browser.home() }
            HoloButton("DIŞ TARAYICI", accent = JarvisColors.dim) { if (st.url.isNotBlank()) x.openExternal(st.url) }
        }
    }
}

// ===================================================================== MENÜ

@Composable
fun MenuScreen(onOpen: (String) -> Unit) {
    val items = listOf(
        Triple("UYGULAMA", "Telefondaki uygulamalar", "APPS"),
        Triple("SOHBET", "Konuşma geçmişi", "CHAT"),
        Triple("HAFIZA", "Hatırladıklarım, takip edilen konular, geri al", "MEMORY"),
        Triple("ARAYÜZ", "Avatar, renkler, 3B efektler", "UI"),
        Triple("SUNUCU", "Yapay zekâ sağlayıcıları ve canlı mod", "SERVER"),
        Triple("SİSTEM", "İzinler, ses servisleri, sürüm", "SYSTEM"),
        Triple("AYARLAR", "Ses, konuşma, gizlilik", "SETTINGS"),
    )
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("MENÜ", "Tüm bölümler")
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (title, sub, id) ->
                    HoloPanel(modifier = Modifier.weight(1f).clickable { onOpen(id) }) {
                        Text(title, color = JarvisColors.violet, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(sub, color = JarvisColors.dim, fontSize = 12.sp, lineHeight = 16.sp, minLines = 2, maxLines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ===================================================================== HAFIZA

@Composable
fun MemoryScreen(s: HoloState, a: HoloActions, x: HoloExtras) {
    val facts by x.memory.facts.collectAsStateWithLifecycle()
    val topics by x.topics.topics.collectAsStateWithLifecycle()
    var newFact by rememberSaveable { mutableStateOf("") }
    var newTopic by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("HAFIZA", "Senin hakkında hatırladıklarım — tamamı bu telefonda saklanır")

        HoloPanel(title = "HATIRLADIKLARIM", trailing = { Text("${facts.size}", color = JarvisColors.dim, fontFamily = Mono, fontSize = 11.sp) }) {
            HoloSwitchRow(
                "Yapay zekâ hafızayı kullansın",
                "Açıkken hafızanın kısa özeti sorularına bağlam olarak eklenir (seçili sağlayıcıya gönderilir). Kapalıyken hafıza yalnızca telefonda kalır.",
                s.settings.memoryEnabled,
            ) { b -> a.updateSettings { it.copy(memoryEnabled = b) } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloField(newFact, { newFact = it }, placeholder = "Yeni bilgi (ör. En sevdiğim renk lacivert)", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HoloButton("EKLE", filled = true, enabled = newFact.isNotBlank()) {
                    when (val r = x.memory.put(null, null, newFact)) {
                        is MemoryStore.PutResult.Saved -> { newFact = ""; x.toast("Hafızaya eklendi.") }
                        is MemoryStore.PutResult.Refused -> x.toast(r.reason)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (facts.isEmpty()) BodyText("Henüz bir şey hatırlamıyorum. Sesle \"Bunu hatırla: ...\" diyebilirsin.")
            facts.sortedWith(compareBy({ MemoryStore.CATEGORIES.indexOf(it.category) }, { -it.updated })).groupBy { it.category }.forEach { (cat, list) ->
                Spacer(Modifier.height(6.dp))
                FieldLabel(cat.uppercase(TR))
                list.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.value, color = JarvisColors.text, fontSize = 14.sp)
                            Text(f.key.replace('_', ' '), color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp)
                        }
                        HoloButton("✕", accent = JarvisColors.dim) { x.memory.remove(f.key) }
                    }
                }
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HoloButton(if (confirmClear) "EMİN MİSİN? TÜMÜNÜ SİL" else "TÜMÜNÜ SİL", accent = JarvisColors.danger) {
                    if (confirmClear) { x.memory.clear(); confirmClear = false } else confirmClear = true
                }
            }
        }

        HoloPanel(title = "TAKİP EDİLEN KONULAR", accent = JarvisColors.gold) {
            BodyText("Günde bir kez internette yeni gelişme ararım; yeni bir şey varsa bildirim gönderirim. Yapay zekâ sağlayıcısı ve bildirim izni gerekir.", size = 12)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloField(newTopic, { newTopic = it }, placeholder = "Konu (ör. uzay araştırmaları)", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HoloButton("EKLE", accent = JarvisColors.gold, filled = true, enabled = newTopic.isNotBlank()) {
                    val err = x.topics.add(newTopic)
                    if (err == null) { newTopic = ""; a.requestNotifications() } else x.toast(err)
                }
            }
            topics.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = JarvisColors.text, fontSize = 14.sp)
                        Text(
                            if (t.lastCheck == 0L) "Henüz kontrol edilmedi" else "Son kontrol: " + DateTimeFormatter.ofPattern("d MMM HH:mm", TR).format(Instant.ofEpochMilli(t.lastCheck).atZone(ZoneId.systemDefault())),
                            color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp,
                        )
                        if (t.lastSummary.isNotBlank()) Text(t.lastSummary, color = JarvisColors.dim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    HoloButton("✕", accent = JarvisColors.dim) { x.topics.remove(t.name) }
                }
            }
            if (topics.isNotEmpty()) { Spacer(Modifier.height(6.dp)); HoloButton("ŞİMDİ KONTROL ET", accent = JarvisColors.gold) { x.checkTopicsNow() } }
        }

        HoloPanel(title = "GERİ AL", accent = JarvisColors.dim) {
            BodyText("Asistanın yaptığı son değişiklikler (takvime eklenen etkinlik, hafıza kaydı). Sesle: \"Geri al\".", size = 12)
            Spacer(Modifier.height(6.dp))
            if (x.undoLabels.isEmpty()) BodyText("Geri alınabilecek bir değişiklik yok.")
            x.undoLabels.forEachIndexed { i, l -> Text("${i + 1}. $l", color = JarvisColors.text, fontSize = 13.sp) }
            if (x.undoLabels.isNotEmpty()) { Spacer(Modifier.height(8.dp)); HoloButton("SON İŞLEMİ GERİ AL", accent = JarvisColors.violet) { x.undo() } }
        }
        Spacer(Modifier.height(8.dp))
    }
}
