package com.moscilez.track.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moscilez.track.R
import com.moscilez.track.tracking.LatLon
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.PlexMono
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

enum class MarkerKind { START, END, CURRENT, STOP, PEAK }

@Immutable
data class MapMarker(val point: LatLon, val kind: MarkerKind)

@Immutable
data class RouteMapData(
    /** Route split into recorded segments (pauses break the line). */
    val segments: List<List<LatLon>>,
    val markers: List<MapMarker> = emptyList(),
)

/**
 * Pluggable map rendering. The default shows OpenStreetMap streets ([OsmMapProvider]);
 * [CanvasMapProvider] is a fully offline vector renderer without a base map.
 */
interface MapProvider {
    @Composable
    fun RouteMap(data: RouteMapData, modifier: Modifier, interactive: Boolean, showGrid: Boolean, description: String)
}

val LocalMapProvider = staticCompositionLocalOf<MapProvider> { OsmMapProvider }

@Composable
fun RouteMap(data: RouteMapData, modifier: Modifier = Modifier, interactive: Boolean = false, showGrid: Boolean = true) {
    LocalMapProvider.current.RouteMap(data, modifier, interactive, showGrid, stringResource(R.string.cd_route_map))
}

/** Offline renderer: local metric projection, grid, scale bar, markers, pinch-zoom and pan. */
object CanvasMapProvider : MapProvider {
    private const val R_EARTH = 6_371_000.0

    @Composable
    override fun RouteMap(data: RouteMapData, modifier: Modifier, interactive: Boolean, showGrid: Boolean, description: String) {
        val mt = LocalMT.current
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val measurer = rememberTextMeasurer()
        val unitKm = stringResource(R.string.unit_km)
        val unitM = stringResource(R.string.unit_m)
        val all = remember(data) { data.segments.flatten() + data.markers.map { it.point } }
        val gestures = if (interactive) Modifier.pointerInput(Unit) {
            detectTransformGestures { centroid, p, z, _ ->
                val newZoom = (zoom * z).coerceIn(1f, 60f)
                val k = newZoom / zoom
                pan = (pan - centroid) * k + centroid + p
                zoom = newZoom
            }
        }.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { c ->
                val newZoom = (zoom * 2f).coerceAtMost(60f)
                pan = (pan - c) * (newZoom / zoom) + c
                zoom = newZoom
            })
        } else Modifier

        Box(modifier.clipToBounds().background(mt.mapGround)) {
            Canvas(Modifier.fillMaxSize().then(gestures).semantics { contentDescription = description }) {
                if (all.isEmpty()) return@Canvas
                val lat0 = all.sumOf { it.lat } / all.size
                val kx = cos(lat0 * PI / 180) * R_EARTH * PI / 180
                val ky = R_EARTH * PI / 180
                var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
                var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                for (p in all) {
                    val x = p.lon * kx; val y = p.lat * ky
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
                // At least a 300 m window so a standing user is not zoomed to centimetres.
                val minExtent = 300.0
                val cxm = (minX + maxX) / 2; val cym = (minY + maxY) / 2
                val ext = max(max(maxX - minX, maxY - minY), minExtent)
                val padPx = 28.dp.toPx()
                val base = (minOf(size.width, size.height) - 2 * padPx) / ext
                val scale = base * zoom
                val center = Offset(size.width / 2, size.height / 2) + pan
                fun proj(p: LatLon) = Offset(
                    (center.x + (p.lon * kx - cxm) * scale).toFloat(),
                    (center.y - (p.lat * ky - cym) * scale).toFloat(),
                )
                val mPerPx = 1.0 / scale
                if (showGrid) drawGrid(mt.mapGrid, mPerPx, center, cxm, cym, scale)
                val routeStroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (seg in data.segments) {
                    if (seg.size < 2) continue
                    val path = Path()
                    seg.forEachIndexed { i, p -> val o = proj(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    drawPath(path, mt.accent, style = routeStroke)
                }
                for (m in data.markers) drawMarker(m.kind, proj(m.point), mt.accent, mt.surface, mt.ink)
                drawScaleBar(mPerPx, mt.ink, mt.mapGround, measurer, unitKm, unitM)
            }
            if (interactive && (zoom != 1f || pan != Offset.Zero)) {
                IconButton(
                    onClick = { zoom = 1f; pan = Offset.Zero },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 28.dp).size(48.dp)
                        .background(mt.surface, CircleShape).border(1.dp, mt.line, CircleShape),
                ) { Icon(Icons.Filled.MyLocation, stringResource(R.string.cd_recenter), tint = mt.ink) }
            }
        }
    }

    private fun niceStep(target: Double): Double {
        val e = 10.0.pow(floor(log10(target)))
        val f = target / e
        return e * when { f < 1.5 -> 1.0; f < 3.5 -> 2.0; f < 7.5 -> 5.0; else -> 10.0 }
    }

    private fun DrawScope.drawGrid(color: Color, mPerPx: Double, center: Offset, cxm: Double, cym: Double, scale: Double) {
        val step = niceStep(mPerPx * size.minDimension / 5)
        val left = cxm + (0 - center.x) / scale
        val right = cxm + (size.width - center.x) / scale
        var x = floor(left / step) * step
        while (x <= right) {
            val px = (center.x + (x - cxm) * scale).toFloat()
            drawLine(color, Offset(px, 0f), Offset(px, size.height), 1f)
            x += step
        }
        val top = cym + (center.y - 0) / scale
        val bottom = cym + (center.y - size.height) / scale
        var y = floor(bottom / step) * step
        while (y <= top) {
            val py = (center.y - (y - cym) * scale).toFloat()
            drawLine(color, Offset(0f, py), Offset(size.width, py), 1f)
            y += step
        }
    }

    private fun DrawScope.drawMarker(kind: MarkerKind, o: Offset, accent: Color, surface: Color, ink: Color) {
        val r = 7.dp.toPx()
        when (kind) {
            MarkerKind.START -> {
                drawCircle(surface, r + 2.dp.toPx(), o)
                drawCircle(accent, r, o, style = Stroke(3.dp.toPx()))
            }
            MarkerKind.END, MarkerKind.CURRENT -> {
                drawCircle(surface, r + 3.dp.toPx(), o)
                drawCircle(accent, r, o)
            }
            MarkerKind.STOP -> {
                val s = 5.dp.toPx()
                drawRect(surface, Offset(o.x - s - 1.5f, o.y - s - 1.5f), Size(2 * s + 3f, 2 * s + 3f))
                drawRect(ink, Offset(o.x - s, o.y - s), Size(2 * s, 2 * s))
            }
            MarkerKind.PEAK -> {
                val s = 6.dp.toPx()
                val p = Path().apply { moveTo(o.x, o.y - s); lineTo(o.x + s, o.y + s * 0.8f); lineTo(o.x - s, o.y + s * 0.8f); close() }
                drawPath(p, surface, style = Stroke(3.dp.toPx()))
                drawPath(p, ink)
            }
        }
    }

    private fun DrawScope.drawScaleBar(mPerPx: Double, ink: Color, ground: Color, measurer: androidx.compose.ui.text.TextMeasurer, km: String, m: String) {
        val meters = niceStep(mPerPx * size.width / 4)
        val px = (meters / mPerPx).toFloat()
        val label = if (meters >= 1000) "${(meters / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} $km" else "${meters.toInt()} $m"
        val right = size.width - 12.dp.toPx()
        val y = size.height - 12.dp.toPx()
        val layout = measurer.measure(label, TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = ink))
        drawRect(ground.copy(alpha = 0.85f), Offset(right - px - 6.dp.toPx(), y - layout.size.height - 10.dp.toPx()), Size(px + 12.dp.toPx(), layout.size.height + 14.dp.toPx()))
        drawLine(ink, Offset(right - px, y), Offset(right, y), 2.dp.toPx())
        drawLine(ink, Offset(right - px, y), Offset(right - px, y - 5.dp.toPx()), 2.dp.toPx())
        drawLine(ink, Offset(right, y), Offset(right, y - 5.dp.toPx()), 2.dp.toPx())
        drawText(layout, topLeft = Offset(right - layout.size.width, y - layout.size.height - 6.dp.toPx()))
    }
}
