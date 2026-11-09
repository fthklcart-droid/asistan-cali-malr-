package com.fk.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * SRİUS holografik arayüz bileşenleri: cam paneller, köşe çerçeveleri, kesik köşeli düğmeler,
 * nabız atan durum ışıkları ve animasyonlu mikrofon küresi.
 */

val Mono = FontFamily.Monospace

/** Köşelerinde HUD çerçevesi olan cam panel. */
@Composable
fun HoloPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = JarvisColors.violet,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    // Holografik giriş: belirme + aşağıdan kayma + kısa titreme.
    val appear = remember { Animatable(if (HoloFx.animations) 0f else 1f) }
    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(420)) }
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer {
                val v = appear.value
                alpha = (v * (if (v < 0.6f) 0.6f + 0.4f * kotlin.math.abs(kotlin.math.sin(v * 40f)) else 1f)).coerceIn(0f, 1f)
                translationY = (1f - v) * 24.dp.toPx()
                scaleX = 0.97f + 0.03f * v
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.13f), JarvisColors.panel)))
            .border(1.dp, accent.copy(alpha = 0.32f), shape)
            .drawWithContent {
                drawContent()
                val l = 14.dp.toPx(); val w = 2.dp.toPx(); val c = accent.copy(alpha = 0.9f)
                // köşe çerçeveleri
                drawLine(c, Offset(0f, 0f), Offset(l, 0f), w); drawLine(c, Offset(0f, 0f), Offset(0f, l), w)
                drawLine(c, Offset(size.width, 0f), Offset(size.width - l, 0f), w); drawLine(c, Offset(size.width, 0f), Offset(size.width, l), w)
                drawLine(c, Offset(0f, size.height), Offset(l, size.height), w); drawLine(c, Offset(0f, size.height), Offset(0f, size.height - l), w)
                drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), w); drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - l), w)
            }
            .padding(14.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title, color = accent, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.5f), Color.Transparent))))
                if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
            }
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** Kesik köşeli holografik düğme. */
@Composable
fun HoloButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = JarvisColors.violet,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    val c = if (enabled) accent else JarvisColors.dim
    Box(
        modifier.clip(shape)
            .background(c.copy(alpha = if (filled) 0.32f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.8f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else c, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun HoloChip(text: String, active: Boolean, accent: Color = JarvisColors.violet, onClick: (() -> Unit)? = null) {
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Box(
        Modifier.clip(shape)
            .background(if (active) accent.copy(alpha = 0.26f) else Color(0x66000A10))
            .border(1.dp, if (active) accent else JarvisColors.stroke, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (active) Color.White else JarvisColors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

/** Nabız atan durum ışığı. */
@Composable
fun StatusDot(color: Color, pulse: Boolean = true, size: Int = 8) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = if (pulse) a else 1f)))
}

/** Başlıkta ve listelerde kullanılan durum etiketi: ışık + yazı. */
@Composable
fun StatusPill(label: String, color: Color, pulse: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(Color(0x99000A12)).border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, pulse, 7)
        Spacer(Modifier.width(6.dp))
        Text(label, color = JarvisColors.text, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, color = JarvisColors.dim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun BodyText(text: String, color: Color = JarvisColors.dim, size: Int = 13) {
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 5).sp)
}

@Composable
fun HoloField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = JarvisColors.dim.copy(alpha = 0.7f), fontSize = 13.sp) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp, color = JarvisColors.text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0x55000A12), unfocusedContainerColor = Color(0x55000A12),
            focusedBorderColor = JarvisColors.violet, unfocusedBorderColor = JarvisColors.stroke,
            cursorColor = JarvisColors.violet,
        ),
        shape = RoundedCornerShape(4.dp),
    )
}

@Composable
fun HoloSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = JarvisColors.text, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = JarvisColors.dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = JarvisColors.violet.copy(alpha = 0.7f),
                uncheckedThumbColor = JarvisColors.dim, uncheckedTrackColor = Color(0x33000A12), uncheckedBorderColor = JarvisColors.stroke,
            ),
        )
    }
}

@Composable
fun HoloSliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(title, color = JarvisColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = JarvisColors.violet, fontFamily = Mono, fontSize = 13.sp)
        }
        Slider(
            value = value, valueRange = range, onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = JarvisColors.violet, activeTrackColor = JarvisColors.violet, inactiveTrackColor = JarvisColors.stroke),
        )
    }
}

/** İnce ızgara ve tarama çizgisi olan holografik arka plan (avatar olmayan ekranlar için). */
@Composable
fun HoloBackdrop(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "s")
    val grid = JarvisColors.violet.copy(alpha = 0.06f)
    val accent = JarvisColors.violet
    val showGrid = HoloFx.grid
    Canvas(modifier.fillMaxSize().background(JarvisColors.bg)) {
        if (!showGrid) return@Canvas
        val step = 28.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        val sy = scan * size.height
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.07f), Color.Transparent), startY = sy - 80f, endY = sy + 80f),
            topLeft = Offset(0f, sy - 80f), size = Size(size.width, 160f),
        )
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width), radius = size.width, center = Offset(size.width / 2, 0f))
    }
}

/**
 * Animasyonlu mikrofon küresi: dönen kesikli halkalar + ses seviyesine göre büyüyen iç halka.
 */
@Composable
fun MicOrb(label: String, color: Color, level: Float, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 2200 else 9000, easing = LinearEasing)), label = "r")
    val breathe by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "b")
    Box(Modifier.size(84.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.04f))), radius = r * 0.72f * breathe)
            drawCircle(color.copy(alpha = 0.25f + 0.6f * level), radius = r * (0.62f + 0.18f * level), style = Stroke(2.dp.toPx()))
            rotate(rot) {
                for (i in 0 until 3) {
                    drawArc(color, startAngle = i * 120f, sweepAngle = 70f, useCenter = false,
                        topLeft = Offset(r * 0.08f, r * 0.08f), size = Size(r * 1.84f, r * 1.84f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            rotate(-rot * 1.6f) {
                for (i in 0 until 6) {
                    drawArc(color.copy(alpha = 0.5f), startAngle = i * 60f, sweepAngle = 18f, useCenter = false,
                        topLeft = Offset(r * 0.2f, r * 0.2f), size = Size(r * 1.6f, r * 1.6f), style = Stroke(1.dp.toPx()))
                }
            }
        }
        Text(label, color = Color.White, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Ses seviyesi çubukları (ekolayzer görünümü). */
@Composable
fun LevelBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val ph by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Canvas(modifier.size(width = 64.dp, height = 18.dp)) {
        val n = 9
        val bw = size.width / (n * 1.6f)
        for (i in 0 until n) {
            val shape = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(ph + i * 0.7f))
            val h = (0.12f + level * shape).coerceIn(0.08f, 1f) * size.height
            val x = i * bw * 1.6f
            drawRect(color.copy(alpha = 0.4f + 0.6f * level), topLeft = Offset(x, size.height - h), size = Size(bw, h))
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = JarvisColors.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = JarvisColors.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = JarvisColors.text, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(subtitle, color = JarvisColors.dim, fontSize = 12.sp)
    }
}

/**
 * Bölme geçişlerinde holografik tarama: parlak bir çizgi ekranı yukarıdan aşağı süpürür,
 * arkasında kısa bir parazit/ızgara izi bırakır. [key] değiştikçe yeniden oynar.
 */
@Composable
fun HoloSweep(key: Any) {
    if (!HoloFx.animations) return
    val p = remember { Animatable(1f) }
    LaunchedEffect(key) { p.snapTo(0f); p.animateTo(1f, tween(520, easing = LinearEasing)) }
    val v = p.value
    if (v >= 1f) return
    val accent = JarvisColors.violet
    Canvas(Modifier.fillMaxSize()) {
        val y = v * size.height
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.18f)), startY = y - 220f, endY = y),
            topLeft = Offset(0f, y - 220f), size = Size(size.width, 220f))
        drawLine(accent.copy(alpha = 0.9f * (1f - v)), Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
        // parazit çizgileri
        val seed = (v * 997).toInt()
        for (i in 0 until 6) {
            val yy = ((seed * (i + 3) * 37) % 1000) / 1000f * y
            drawLine(accent.copy(alpha = 0.25f * (1f - v)), Offset(0f, yy), Offset(size.width * (0.3f + 0.7f * ((i * 13 + seed) % 10) / 10f), yy), 1.dp.toPx())
        }
    }
}
