package com.fk.jarvis.ui

import android.net.TrafficStats
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fk.jarvis.apps.DeviceStatus
import com.fk.jarvis.core.AssistantState
import com.fk.jarvis.core.ClipboardTools
import com.fk.jarvis.data.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val TRC = Locale.forLanguageTag("tr-TR")
private val OKC = Color(0xFF3DFFB0)

/** Komuta merkezinin ihtiyaç duyduğu, Activity'nin sağladığı işlevler. */
class CenterHooks(
    val snapshot: () -> DeviceStatus.Snapshot,
    /** Panodaki metni okur (yalnızca kullanıcı düğmeye bastığında çağrılır). */
    val readClipboard: () -> String?,
    val copy: (String) -> Unit,
    /** Yapay zekâya sistem + kullanıcı metni gönderir, yanıt metnini döndürür; hata iletisini istisna olarak fırlatır. */
    val ask: suspend (system: String, user: String) -> String,
)

/**
 * MERKEZ — komuta merkezi: 3B reaktör çekirdeği (asistanın durumuna ve sesine tepki verir), canlı telefon göstergeleri,
 * ağ hızı grafiği, döküm tarzı kayıt, görev/rutin paneli ve pano zekâsı. Tüm değerler cihazdan okunan gerçek verilerdir.
 */
@Composable
fun CommandCenterScreen(s: HoloState, x: HoloExtras, openPlan: () -> Unit, openAgent: () -> Unit) {
    val hooks = x.center
    val level by s.level.collectAsStateWithLifecycle()
    var snap by remember { mutableStateOf<DeviceStatus.Snapshot?>(null) }
    var down by remember { mutableStateOf(listOf<Float>()) }   // KB/sn örnekleri
    var up by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes(); var lastTx = TrafficStats.getTotalTxBytes(); var lastT = System.nanoTime()
        while (true) {
            snap = withContext(Dispatchers.IO) { runCatching { hooks.snapshot() }.getOrNull() }
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val t = System.nanoTime()
            val dt = ((t - lastT) / 1e9f).coerceAtLeast(0.2f)
            if (rx >= 0 && lastRx >= 0) {
                down = (down + ((rx - lastRx).coerceAtLeast(0) / 1024f / dt)).takeLast(40)
                up = (up + ((tx - lastTx).coerceAtLeast(0) / 1024f / dt)).takeLast(40)
            }
            lastRx = rx; lastTx = tx; lastT = t
            delay(2000)
        }
    }
    val tasks by x.tasks.tasks.collectAsStateWithLifecycle()
    val routines by x.routines.routines.collectAsStateWithLifecycle()
    val agentUi by x.agent.ui.collectAsStateWithLifecycle()
    val stColor = stateColor(s.ui.state)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("MERKEZ", "Komuta merkezi · " + SimpleDateFormat("HH:mm", TRC).format(Date())) }
            StatusPill(centerState(s.ui.state), stColor, pulse = s.ui.state != AssistantState.IDLE)
        }

        // ---------------------------------------------------------------- REAKTÖR + GÖSTERGELER
        HoloPanel(title = "ÇEKİRDEK") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.15f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    ReactorCore(stColor, level, active = s.ui.state != AssistantState.IDLE, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val sn = snap
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RingGauge("PİL", sn?.batteryPct?.let { it / 100f }, sn?.batteryPct?.let { "%$it" } ?: "—",
                            if ((sn?.batteryPct ?: 100) <= 20) JarvisColors.danger else if (sn?.charging == true) OKC else JarvisColors.violet, Modifier.weight(1f))
                        val ramUsed = sn?.let { 1f - it.ramAvailMb.toFloat() / it.ramTotalMb.coerceAtLeast(1) }
                        RingGauge("BELLEK", ramUsed, ramUsed?.let { "%${(it * 100).toInt()}" } ?: "—", if ((ramUsed ?: 0f) > 0.88f) JarvisColors.danger else JarvisColors.violet, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val disk = sn?.let { 1f - (it.storageFreeGb / it.storageTotalGb.coerceAtLeast(0.1)).toFloat() }
                        RingGauge("DEPOLAMA", disk, sn?.let { "${it.storageFreeGb.toInt()}G" } ?: "—", if ((disk ?: 0f) > 0.92f) JarvisColors.danger else JarvisColors.gold, Modifier.weight(1f))
                        val temp = sn?.batteryTempC
                        RingGauge("SICAKLIK", temp?.let { ((it - 15f) / 35f).coerceIn(0f, 1f) }, temp?.let { "${it.toInt()}°" } ?: "—",
                            if ((temp ?: 0f) >= 42f) JarvisColors.danger else OKC, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Ağ
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(92.dp)) {
                    FieldLabel("AĞ · ${(snap?.network ?: "—").uppercase(TRC)}")
                    Text("↓ ${rate(down.lastOrNull())}", color = OKC, fontFamily = Mono, fontSize = 11.sp)
                    Text("↑ ${rate(up.lastOrNull())}", color = JarvisColors.gold, fontFamily = Mono, fontSize = 11.sp)
                }
                Sparkline(down, up, Modifier.weight(1f).height(44.dp))
            }
            if (snap?.powerSave == true || snap?.thermal != null) {
                Spacer(Modifier.height(6.dp))
                BodyText(listOfNotNull(if (snap?.powerSave == true) "Güç tasarrufu açık" else null, snap?.thermal?.let { "Telefon $it" }).joinToString(" · "), JarvisColors.gold, 12)
            }
        }

        // ---------------------------------------------------------------- GÖREVLER / RUTİNLER
        HoloPanel(title = "GÖREVLER · RUTİNLER", accent = OKC, trailing = { HoloButton("PLAN", accent = OKC) { openPlan() } }) {
            val today = LocalDate.now()
            val open = x.tasks.sorted(tasks.filter { !it.done })
            if (agentUi.running) {
                Row(Modifier.fillMaxWidth().clickable { openAgent() }.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(JarvisColors.violet, pulse = true, size = 8); Spacer(Modifier.width(8.dp))
                    Text("AJAN ÇALIŞIYOR · adım ${agentUi.steps.size} · ${agentUi.goal.take(60)}", color = JarvisColors.violet, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (open.isEmpty()) BodyText("Açık görev yok.")
            open.take(4).forEach { t ->
                val late = t.due != null && t.due < today.toString()
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (late) JarvisColors.danger else if (t.priority == 2) JarvisColors.gold else OKC, pulse = false, size = 6)
                    Spacer(Modifier.width(8.dp))
                    Text(t.title, color = JarvisColors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    t.due?.let { Text(if (late) "GECİKMİŞ" else com.fk.jarvis.apps.CalendarText.dayLabel(LocalDate.parse(it), today).uppercase(TRC), color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp) }
                }
            }
            if (open.size > 4) BodyText("+${open.size - 4} görev daha", size = 11)
            val now = LocalDateTime.now()
            routines.mapNotNull { r -> r.nextFire(now)?.let { r to it } }.minByOrNull { it.second }?.let { (r, at) ->
                Spacer(Modifier.height(6.dp))
                Text("SIRADAKİ RUTİN · ${at.format(DateTimeFormatter.ofPattern("d MMM HH:mm", TRC))} · ${r.title}", color = JarvisColors.gold, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // ---------------------------------------------------------------- PANO ZEKÂSI
        ClipboardPanel(s, x, hooks)

        // ---------------------------------------------------------------- KAYIT
        HoloPanel(title = "KAYIT", accent = JarvisColors.dim) {
            val fmt = remember { SimpleDateFormat("HH:mm:ss", TRC) }
            val last = s.history.takeLast(10)
            if (last.isEmpty()) BodyText("Henüz konuşma yok.")
            last.forEach { e ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(fmt.format(Date(e.time)), color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                    Text(if (e.fromUser) "SEN  " else "JRVS ", color = if (e.fromUser) JarvisColors.gold else JarvisColors.violet, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(e.text, color = if (e.fromUser) JarvisColors.text else JarvisColors.pink, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun centerState(st: AssistantState) = when (st) {
    AssistantState.IDLE -> "HAZIR"; AssistantState.LISTENING -> "DİNLİYOR"; AssistantState.PROCESSING -> "DÜŞÜNÜYOR"
    AssistantState.SPEAKING -> "KONUŞUYOR"; AssistantState.EXECUTING -> "İŞLEM"; AssistantState.CONFIRMING -> "ONAY"; AssistantState.ERROR -> "HATA"
}

private fun rate(kb: Float?): String = when {
    kb == null -> "—"
    kb >= 1024f -> "%.1f MB/s".format(kb / 1024f)
    else -> "%.0f KB/s".format(kb)
}

@Composable
private fun ClipboardPanel(s: HoloState, x: HoloExtras, hooks: CenterHooks) {
    val scope = rememberCoroutineScope()
    var clip by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<ClipboardTools.Mode?>(null) }
    HoloPanel(title = "PANO ZEKÂSI", accent = JarvisColors.violet) {
        if (clip == null) {
            BodyText("Bir metni kopyala, sonra PANOYU AL'a bas: çevir, özetle, açıkla ya da düzelt. Pano yalnızca bu düğmeye bastığında okunur.", size = 12)
            Spacer(Modifier.height(8.dp))
            HoloButton("PANOYU AL", filled = true) {
                val t = hooks.readClipboard()?.trim()
                if (t.isNullOrEmpty()) x.toast("Panoda metin yok.") else { clip = t; result = null }
            }
        } else {
            val text = clip.orEmpty()
            Text(text, color = JarvisColors.dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("${text.length} karakter", color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClipboardTools.Mode.entries.forEach { m ->
                    HoloChip(if (busy == m) "${m.label}…" else m.label, active = busy == m) {
                        if (busy != null) return@HoloChip
                        if (MemoryStore.isSecret(text)) { x.toast("Parola ya da kart bilgisi içeren metni yapay zekâya göndermiyorum."); return@HoloChip }
                        busy = m; result = null
                        scope.launch {
                            result = try { ClipboardTools.parse(hooks.ask(ClipboardTools.system(m), ClipboardTools.user(text))) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { e.message ?: "İşlem yapılamadı." }
                            busy = null
                        }
                    }
                }
                HoloChip("✕ TEMİZLE", active = false, accent = JarvisColors.dim) { clip = null; result = null }
            }
            result?.let { r ->
                Spacer(Modifier.height(10.dp))
                BodyText(r, JarvisColors.text, 14)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton("KOPYALA") { hooks.copy(r); x.toast("Panoya kopyalandı.") }
                    HoloButton("SESLİ OKU", accent = JarvisColors.dim) { x.speak(r) }
                    HoloButton("NOTA KAYDET", accent = JarvisColors.dim) { if (x.notes.add(r) != null) x.toast("Notlara kaydedildi.") else x.toast("Kaydedilemedi.") }
                }
            }
        }
    }
}

// ===================================================================== çizimler

/** Halka gösterge: [fraction] 0..1 (null: veri yok). */
@Composable
private fun RingGauge(label: String, fraction: Float?, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = size.minDimension * 0.11f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(color.copy(alpha = 0.16f), 135f, 270f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                // bölme çentikleri
                for (i in 0..9) {
                    val a = (135f + i * 30f) * PI.toFloat() / 180f
                    val r1 = size.minDimension / 2 - stroke * 1.25f; val r2 = r1 - stroke * 0.35f
                    drawLine(color.copy(alpha = 0.35f), Offset(center.x + cos(a) * r1, center.y + sin(a) * r1), Offset(center.x + cos(a) * r2, center.y + sin(a) * r2), 1.5f)
                }
                if (fraction != null) drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value, color = JarvisColors.text, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = JarvisColors.dim, fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 1.sp, maxLines = 1)
    }
}

/** Ağ hızı grafiği: indirme (dolu alan) ve yükleme (çizgi), son ~80 saniye. */
@Composable
private fun Sparkline(down: List<Float>, up: List<Float>, modifier: Modifier = Modifier) {
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Canvas(modifier.clip(shape).background(Color(0x33000A10)).border(1.dp, JarvisColors.stroke.copy(alpha = 0.5f), shape)) {
        val max = (down + up).maxOrNull()?.coerceAtLeast(16f) ?: 16f
        fun path(v: List<Float>, close: Boolean): Path? {
            if (v.size < 2) return null
            val p = Path()
            val step = size.width / 39f
            val x0 = size.width - (v.size - 1) * step
            v.forEachIndexed { i, y ->
                val px = x0 + i * step; val py = size.height - 2f - (y / max) * (size.height - 6f)
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            if (close) { p.lineTo(size.width, size.height); p.lineTo(x0, size.height); p.close() }
            return p
        }
        for (i in 1..2) drawLine(JarvisColors.stroke.copy(alpha = 0.18f), Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
        path(down, true)?.let { drawPath(it, Brush.verticalGradient(listOf(OKC.copy(alpha = 0.45f), OKC.copy(alpha = 0.02f)))) }
        path(down, false)?.let { drawPath(it, OKC, style = Stroke(2f)) }
        path(up, false)?.let { drawPath(it, JarvisColors.gold, style = Stroke(1.6f)) }
    }
}

/** Fibonacci küresi üzerindeki noktalar (birim küre). */
private val SPHERE: List<FloatArray> = run {
    val n = 240
    val g = PI * (3.0 - sqrt(5.0))
    List(n) { i ->
        val y = 1.0 - 2.0 * (i + 0.5) / n
        val r = sqrt(1.0 - y * y)
        floatArrayOf((cos(g * i) * r).toFloat(), y.toFloat(), (sin(g * i) * r).toFloat())
    }
}

/**
 * 3B reaktör çekirdeği: derinliğe göre parlaklığı değişen nokta küresi + üç jiroskop halkası; yazılımla (Canvas) çizilir.
 * Asistan etkinken hızlanır, ses seviyesiyle genişler ve parlar.
 */
@Composable
private fun ReactorCore(color: Color, level: Float, active: Boolean, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    var smooth by remember { mutableFloatStateOf(0f) }
    val animate = HoloFx.animations
    val target by androidx.compose.runtime.rememberUpdatedState(level)
    LaunchedEffect(animate, active) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                t += dt * (if (active) 1.7f else 0.6f)
                // Ses seviyesi yumuşatılır (ani sıçrama yerine nabız).
                smooth += (target - smooth) * (dt * 12f).coerceAtMost(1f)
            }
        }
    }
    Canvas(modifier) {
        val lv = if (animate) smooth else level
        val c = center
        val R = size.minDimension * 0.30f * (1f + 0.10f * lv)
        val focal = size.minDimension * 1.6f
        val ay = t * 0.55f; val ax = 0.42f + 0.12f * sin(t * 0.4f)
        fun rot(p: FloatArray, rx: Float, ry: Float): FloatArray {
            val cy = cos(ry); val sy = sin(ry)
            val x1 = p[0] * cy + p[2] * sy; val z1 = -p[0] * sy + p[2] * cy
            val cx = cos(rx); val sx = sin(rx)
            return floatArrayOf(x1, p[1] * cx - z1 * sx, p[1] * sx + z1 * cx)
        }
        fun proj(p: FloatArray, radius: Float): Triple<Float, Float, Float> {
            val k = focal / (focal + p[2] * radius)
            return Triple(c.x + p[0] * radius * k, c.y + p[1] * radius * k, k)
        }
        // çekirdek parıltısı
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f + 0.35f * lv), color.copy(alpha = 0.10f), Color.Transparent), c, R * 1.25f), R * 1.25f, c)
        drawCircle(Color.White.copy(alpha = 0.55f + 0.4f * lv), R * (0.10f + 0.05f * lv), c)

        // halkalar: arka yarı önce, küre, sonra ön yarı (derinlik sırası)
        val rings = listOf(Triple(1.45f, 1.25f, 0.9f), Triple(1.72f, 0.35f, -0.6f), Triple(2.0f, -0.75f, 0.35f))
        fun ring(idx: Int, front: Boolean) {
            val (k, tilt, speed) = rings[idx]
            val n = 72
            var prev: Triple<Float, Float, Float>? = null; var prevZ = 0f
            for (i in 0..n) {
                val a = i.toFloat() / n * 2f * PI.toFloat() + t * speed
                var p = floatArrayOf(cos(a), 0f, sin(a))
                p = rot(p, tilt, 0f); p = rot(p, ax * 0.5f, ay * 0.3f)
                val pr = proj(p, R * k)
                val seg = ((i + idx * 5) % 12) < 9
                val pv = prev
                if (pv != null && seg && ((p[2] + prevZ) / 2f < 0f) == front) {
                    val depth = (0.5f - (p[2] + prevZ) / 4f).coerceIn(0f, 1f)
                    drawLine(color.copy(alpha = 0.18f + 0.7f * depth), Offset(pv.first, pv.second), Offset(pr.first, pr.second), (1f + 2.2f * depth) * pr.third, cap = StrokeCap.Round)
                }
                prev = pr; prevZ = p[2]
            }
        }
        for (i in rings.indices) ring(i, front = false)
        drawSphere(color, lv, R, ax, ay, ::rot, ::proj)
        for (i in rings.indices) ring(i, front = true)
    }
}

private fun DrawScope.drawSphere(
    color: Color, lv: Float, R: Float, ax: Float, ay: Float,
    rot: (FloatArray, Float, Float) -> FloatArray, proj: (FloatArray, Float) -> Triple<Float, Float, Float>,
) {
    for (p0 in SPHERE) {
        val p = rot(p0, ax, ay)
        val pr = proj(p, R)
        val depth = (0.5f - p[2] / 2f).coerceIn(0f, 1f)          // 0 arka .. 1 ön
        drawCircle(color.copy(alpha = 0.12f + 0.8f * depth), (0.8f + 2.0f * depth) * pr.third * (1f + 0.5f * lv), Offset(pr.first, pr.second))
    }
}
