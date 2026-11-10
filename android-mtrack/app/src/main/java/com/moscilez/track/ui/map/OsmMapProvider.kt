package com.moscilez.track.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moscilez.track.R
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.PlexMono
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * Street map from OpenStreetMap tiles (cached on device), muted to the app's monochrome
 * palette, with the route and markers drawn on top in the accent colour. Without network,
 * previously viewed areas come from the cache; elsewhere the route still draws on a plain ground.
 */
object OsmMapProvider : MapProvider {
    private const val FOLLOW_ZOOM = 17.0
    private const val SINGLE_POINT_ZOOM = 16.0

    @Composable
    override fun RouteMap(data: RouteMapData, modifier: Modifier, interactive: Boolean, showGrid: Boolean, description: String) {
        val mt = LocalMT.current
        val context = LocalContext.current
        val density = context.resources.displayMetrics.density
        val current = data.markers.lastOrNull { it.kind == MarkerKind.CURRENT }?.point
        // Live recording keeps the user centred until they move the map themselves.
        val live = interactive && current != null
        var follow by remember { mutableStateOf(true) }
        var fitted by remember { mutableStateOf(false) }

        val overlay = remember { RouteOverlay(density) }
        val mapView = remember {
            createMapView(context, interactive, overlay) { follow = false }
        }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, mapView) {
            val observer = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                mapView.onPause()
                mapView.onDetach()
            }
        }

        LaunchedEffect(mt.dark, mt.accent, mt.mapGround) {
            mapView.overlayManager.tilesOverlay.apply {
                setColorFilter(tileFilter(mt.dark))
                loadingBackgroundColor = mt.mapGround.toArgb()
                loadingLineColor = mt.mapGrid.toArgb()
            }
            overlay.setColors(mt.accent, mt.surface, mt.ink)
            mapView.invalidate()
        }

        LaunchedEffect(data, follow) {
            overlay.data = data
            when {
                live && follow && current != null -> mapView.whenLaidOut {
                    if (it.zoomLevelDouble < FOLLOW_ZOOM - 1) it.controller.setZoom(FOLLOW_ZOOM)
                    it.controller.animateTo(GeoPoint(current.lat, current.lon))
                }
                !live && !fitted -> {
                    fitted = true
                    mapView.whenLaidOut { fit(it, data) }
                }
            }
            mapView.invalidate()
        }

        Box(modifier.clipToBounds().background(mt.mapGround).semantics { contentDescription = description }) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (!interactive) {
                // Hit target that consumes nothing: blocks map gestures, lets the parent's click through.
                Box(Modifier.fillMaxSize().pointerInput(Unit) {})
            }
            Text(
                stringResource(R.string.map_attribution),
                fontFamily = PlexMono, fontSize = 9.sp, color = mt.ink,
                modifier = Modifier.align(Alignment.BottomStart).background(mt.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (if (live) !follow else interactive) {
                IconButton(
                    onClick = {
                        if (live) follow = true else mapView.whenLaidOut { fit(it, data) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 24.dp).size(48.dp)
                        .background(mt.surface, CircleShape).border(1.dp, mt.line, CircleShape),
                ) { Icon(Icons.Filled.MyLocation, stringResource(R.string.cd_recenter), tint = mt.ink) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMapView(context: Context, interactive: Boolean, overlay: RouteOverlay, onUserMove: () -> Unit): MapView =
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMultiTouchControls(interactive)
            isClickable = interactive
            controller.setZoom(SINGLE_POINT_ZOOM)
            overlays.add(overlay)
            overlays.add(ScaleBarOverlay(this).apply {
                setAlignBottom(true)
                setScaleBarOffset((12 * context.resources.displayMetrics.density).toInt(), (40 * context.resources.displayMetrics.density).toInt())
                setTextSize(11 * context.resources.displayMetrics.scaledDensity)
                setCentred(false)
            })
            if (interactive) setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_MOVE) onUserMove()
                false
            }
        }

    private fun MapView.whenLaidOut(block: (MapView) -> Unit) {
        if (width > 0 && height > 0) block(this) else addOnFirstLayoutListener { _, _, _, _, _ -> block(this) }
    }

    private fun fit(map: MapView, data: RouteMapData) {
        val pts = data.segments.flatten() + data.markers.map { it.point }
        if (pts.isEmpty()) return
        val north = pts.maxOf { it.lat }; val south = pts.minOf { it.lat }
        val east = pts.maxOf { it.lon }; val west = pts.minOf { it.lon }
        // Under ~300 m of extent, centre at a street-level zoom instead of zooming in absurdly.
        if (north - south < 0.003 && east - west < 0.005) {
            map.controller.setZoom(SINGLE_POINT_ZOOM)
            map.controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
        } else {
            val pad = (32 * map.resources.displayMetrics.density).toInt()
            map.zoomToBoundingBox(BoundingBox(north, east, south, west), false, pad)
        }
    }

    /** Desaturated tiles so the blue route is the only colour; inverted for the dark theme. */
    private fun tileFilter(dark: Boolean): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0.12f) }
        if (dark) {
            m.postConcat(ColorMatrix(floatArrayOf(
                -0.85f, 0f, 0f, 0f, 235f,
                0f, -0.85f, 0f, 0f, 235f,
                0f, 0f, -0.85f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f,
            )))
        } else {
            // Slightly warm, lightened paper tone matching the app background.
            m.postConcat(ColorMatrix(floatArrayOf(
                0.92f, 0f, 0f, 0f, 20f,
                0f, 0.92f, 0f, 0f, 18f,
                0f, 0f, 0.9f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return ColorMatrixColorFilter(m)
    }
}

/** Draws route segments (pauses break the line) and markers using the map projection. */
private class RouteOverlay(private val density: Float) : Overlay() {
    var data: RouteMapData = RouteMapData(emptyList())
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private var accent = 0; private var surface = 0; private var ink = 0
    private val pt = Point()
    private val path = Path()

    fun setColors(accent: Color, surface: Color, ink: Color) {
        this.accent = accent.toArgb(); this.surface = surface.toArgb(); this.ink = ink.toArgb()
        halo.color = this.surface.withAlpha(0xCC)
        line.color = this.accent
    }

    private fun Int.withAlpha(a: Int) = (this and 0x00FFFFFF) or (a shl 24)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        for (seg in data.segments) {
            if (seg.size < 2) continue
            path.reset()
            seg.forEachIndexed { i, p ->
                proj.toPixels(GeoPoint(p.lat, p.lon), pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            canvas.drawPath(path, halo)
            canvas.drawPath(path, line)
        }
        val r = 7 * density
        for (m in data.markers) {
            proj.toPixels(GeoPoint(m.point.lat, m.point.lon), pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            when (m.kind) {
                MarkerKind.START -> {
                    fill.color = surface; canvas.drawCircle(x, y, r + 2 * density, fill)
                    ring.color = accent; canvas.drawCircle(x, y, r, ring)
                }
                MarkerKind.END, MarkerKind.CURRENT -> {
                    fill.color = surface; canvas.drawCircle(x, y, r + 3 * density, fill)
                    fill.color = accent; canvas.drawCircle(x, y, r, fill)
                }
                MarkerKind.STOP -> {
                    val s = 5 * density
                    fill.color = surface; canvas.drawRect(x - s - 2, y - s - 2, x + s + 2, y + s + 2, fill)
                    fill.color = ink; canvas.drawRect(x - s, y - s, x + s, y + s, fill)
                }
                MarkerKind.PEAK -> {
                    val s = 6 * density
                    val tri = Path().apply { moveTo(x, y - s); lineTo(x + s, y + s * 0.8f); lineTo(x - s, y + s * 0.8f); close() }
                    ring.color = surface; canvas.drawPath(tri, ring)
                    fill.color = ink; canvas.drawPath(tri, fill)
                }
            }
        }
    }

}
