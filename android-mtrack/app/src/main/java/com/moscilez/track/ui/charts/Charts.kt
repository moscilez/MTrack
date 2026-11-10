package com.moscilez.track.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.PlexMono
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private fun niceMax(v: Float): Float {
    if (v <= 0f) return 1f
    val e = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    val f = v / e
    return e * when { f <= 1f -> 1f; f <= 2f -> 2f; f <= 3f -> 3f; f <= 5f -> 5f; else -> 10f }
}

/**
 * Line chart with axis labels. [points] are (x, y) in display units; [formatX] formats the
 * x labels. Draws with a short left-to-right reveal.
 */
@Composable
fun LineChart(
    points: List<Pair<Float, Float>>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    formatX: (Float) -> String = { it.toInt().toString() },
    formatY: (Float) -> String = { it.toInt().toString() },
    fixedMinY: Float? = 0f,
) {
    val mt = LocalMT.current
    val measurer = rememberTextMeasurer()
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(700)) }
    val style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = mt.ink2)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (points.size < 2) return@Canvas
        val minX = points.first().first
        val maxX = max(points.last().first, minX + 0.0001f)
        val rawMinY = fixedMinY ?: points.minOf { it.second }
        val maxY = niceMax(points.maxOf { it.second } - (fixedMinY ?: 0f).coerceAtMost(0f))
        val minY = if (fixedMinY != null) fixedMinY else floor(rawMinY)
        val top = maxY.coerceAtLeast(minY + 1f)
        val yLabelW = measurer.measure(formatY(top), style).size.width + 8.dp.toPx()
        val xLabelH = measurer.measure("0", style).size.height + 6.dp.toPx()
        val w = size.width - yLabelW
        val h = size.height - xLabelH
        fun px(x: Float) = yLabelW + (x - minX) / (maxX - minX) * w
        fun py(y: Float) = h - (y - minY) / (top - minY) * h
        // Axes and gridlines.
        for (i in 0..2) {
            val v = minY + (top - minY) * i / 2f
            val y = py(v)
            drawLine(if (i == 0) mt.ink2 else mt.line, Offset(yLabelW, y), Offset(size.width, y), 1f)
            val l = measurer.measure(formatY(v), style)
            drawText(l, topLeft = Offset(yLabelW - l.size.width - 6.dp.toPx(), y - l.size.height / 2f))
        }
        drawLine(mt.ink2, Offset(yLabelW, 0f), Offset(yLabelW, h), 1f)
        for (i in 0..4) {
            val xv = minX + (maxX - minX) * i / 4f
            val l = measurer.measure(formatX(xv), style)
            val x = (px(xv) - l.size.width / 2f).coerceIn(yLabelW, size.width - l.size.width)
            drawText(l, topLeft = Offset(x, h + 4.dp.toPx()))
        }
        val path = Path()
        val limit = minX + (maxX - minX) * reveal.value
        var started = false
        for ((x, y) in points) {
            if (x > limit) break
            val o = Offset(px(x), py(y.coerceIn(minY, top)))
            if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
        }
        drawPath(path, mt.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Vertical bars with labels; the last (current) bar is accented, earlier ones are ink. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    highlightIndex: Int = values.lastIndex,
) {
    val mt = LocalMT.current
    val measurer = rememberTextMeasurer()
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(600)) }
    val style = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = mt.ink2)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val labelH = measurer.measure("0", style).size.height + 6.dp.toPx()
        val h = size.height - labelH
        val maxV = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val slot = size.width / values.size
        val bw = (slot * 0.56f).coerceAtMost(36.dp.toPx())
        drawLine(mt.ink2, Offset(0f, h), Offset(size.width, h), 1f)
        values.forEachIndexed { i, v ->
            val bh = (v / maxV) * (h - 4.dp.toPx()) * grow.value
            val x = slot * i + (slot - bw) / 2
            val color = if (i == highlightIndex) mt.accent else mt.ink
            if (v > 0f) drawRect(color, Offset(x, h - max(bh, 2f)), Size(bw, max(bh, 2f)))
            labels.getOrNull(i)?.let {
                val l = measurer.measure(it, style)
                drawText(l, topLeft = Offset(slot * i + (slot - l.size.width) / 2, h + 4.dp.toPx()))
            }
        }
    }
}

fun ceilTo(v: Float, step: Float) = ceil(v / step) * step
