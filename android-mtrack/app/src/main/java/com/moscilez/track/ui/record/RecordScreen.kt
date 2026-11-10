package com.moscilez.track.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.tracking.GpsState
import com.moscilez.track.tracking.LiveTracking
import com.moscilez.track.tracking.TrackingError
import com.moscilez.track.tracking.TrackingPhase
import com.moscilez.track.tracking.TrackingService
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.FullscreenLoading
import com.moscilez.track.ui.components.KeepScreenOn
import com.moscilez.track.ui.components.MetricBlock
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.VDivider
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.components.rememberNow
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.map.MapMarker
import com.moscilez.track.ui.map.MarkerKind
import com.moscilez.track.ui.map.RouteMap
import com.moscilez.track.ui.map.RouteMapData
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import com.moscilez.track.domain.track.TrackMath
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordViewModel(private val c: AppContainer) : ViewModel() {
    val tracking: StateFlow<LiveTracking> = c.trackingState.state
    val settings: StateFlow<UserSettingsEntity> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettingsEntity())

    fun consumeFinished() = c.trackingState.consumeFinished()
    fun clearError() = c.trackingState.clearError()
}

@Composable
fun gpsLabel(state: GpsState): String = stringResource(
    when (state) {
        GpsState.GOOD -> R.string.gps_good
        GpsState.WEAK -> R.string.gps_weak
        GpsState.SEARCHING -> R.string.gps_searching
        GpsState.DISABLED -> R.string.gps_disabled
        GpsState.NO_PERMISSION -> R.string.gps_no_permission
        GpsState.APPROXIMATE -> R.string.gps_approx
    }
)

@Composable
fun RecordScreen(onClose: () -> Unit, onFinished: (Long) -> Unit) {
    val vm = appViewModel { RecordViewModel(it) }
    val t by vm.tracking.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val now by rememberNow(500)
    var confirmFinish by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(t.phase, t.finishedSessionId) {
        val id = t.finishedSessionId
        if (t.phase == TrackingPhase.IDLE && id != null) {
            vm.consumeFinished()
            onFinished(id)
        }
    }
    BackHandler(onBack = onClose)
    KeepScreenOn(t.isActive && (t.type.isVehicle || settings.backgroundTracking.not()))

    val stop = { TrackingService.command(ctx, TrackingService.ACTION_STOP) }
    val pauseToggle = {
        val paused = t.phase == TrackingPhase.PAUSED || t.phase == TrackingPhase.AUTO_PAUSED
        TrackingService.command(ctx, if (paused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE)
    }

    Box(Modifier.fillMaxSize().background(mt.bg)) {
        when {
            t.phase == TrackingPhase.FINISHING || (t.phase == TrackingPhase.IDLE && t.finishedSessionId != null) -> FullscreenLoading()
            !t.isActive -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = ScreenPadding)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cd_close)) }
                }
                EmptyState(stringResource(R.string.record_none_title), stringResource(R.string.record_none_body), action = stringResource(R.string.action_close), onAction = onClose)
            }
            t.type.isVehicle -> VehicleRecord(t, settings, now, onClose, pauseToggle, stop)
            else -> SportRecord(t, settings, now, onClose, pauseToggle) { confirmFinish = true }
        }
    }

    if (confirmFinish) {
        ConfirmDialog(
            title = stringResource(R.string.record_finish_title),
            body = stringResource(R.string.record_finish_body),
            confirm = stringResource(R.string.action_finish),
            onConfirm = { confirmFinish = false; stop() },
            onDismiss = { confirmFinish = false },
        )
    }
    t.error?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.error_title),
            body = stringResource(
                when (e) {
                    TrackingError.PERMISSION_DENIED -> R.string.err_permission
                    TrackingError.GPS_DISABLED -> R.string.err_gps_disabled
                    TrackingError.START_FAILED -> R.string.err_start_failed
                    TrackingError.STORAGE_FULL -> R.string.err_storage_full
                    TrackingError.DATABASE -> R.string.err_database
                }
            ),
            confirm = stringResource(R.string.action_ok),
            onConfirm = { vm.clearError(); if (!t.isActive) onClose() },
            onDismiss = { vm.clearError() },
        )
    }
}

@Composable
private fun StatusLine(t: LiveTracking, onClose: () -> Unit) {
    val mt = LocalMT.current
    val paused = t.phase == TrackingPhase.PAUSED || t.phase == TrackingPhase.AUTO_PAUSED
    val dot = when (t.gps) {
        GpsState.GOOD -> mt.accent
        GpsState.WEAK, GpsState.APPROXIMATE, GpsState.SEARCHING -> Color(0xFFE0A100)
        else -> mt.danger
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val status = buildString {
            append(stringResource(t.type.label()))
            append(" · ")
            append(gpsLabel(t.gps))
            if (paused) {
                append(" · ")
                append(stringResource(if (t.phase == TrackingPhase.AUTO_PAUSED) R.string.record_auto_paused else R.string.record_paused))
            }
        }
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Caption(status, color = mt.ink)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).background(dot, CircleShape))
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cd_minimize_record)) }
    }
}

/** Fits monospaced text of [chars] glyphs into the available width. */
private fun fittedSize(width: Dp, chars: Int, max: Float): Float =
    // PlexMono advance is ~0.6 em; dp ~ sp at default font scale.
    (width.value / (chars * 0.6f)).coerceAtMost(max)

@Composable
private fun SportRecord(t: LiveTracking, s: UserSettingsEntity, now: Long, onClose: () -> Unit, onPause: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val units = s.units
    val paused = t.phase == TrackingPhase.PAUSED || t.phase == TrackingPhase.AUTO_PAUSED
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).widthIn(max = ContentMaxWidth).align(Alignment.CenterHorizontally)) {
            StatusLine(t, onClose)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val clock = Formatters.clock(t.activeMs(now))
                val size = fittedSize(maxWidth, clock.length, 84f) / fontScale
                Text(
                    clock,
                    style = Num.hero.copy(fontSize = size.sp, lineHeight = (size * 1.08f).sp),
                    color = if (paused) mt.ink2 else mt.ink,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = Formatters.duration(ctx, t.activeMs(now)) },
                )
            }
            Text(
                Formatters.distance(ctx, t.distanceM, units),
                style = Num.xl, maxLines = 1,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                val avg = t.avgSpeed(now)
                if (t.type.showsPace && s.showPace) {
                    MetricBlock(stringResource(R.string.metric_pace), Formatters.paceValue(TrackMath.paceSecPerKm(t.currentSpeed), units), Formatters.paceUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                    VDivider(Modifier.fillMaxHeight()); Spacer(Modifier.width(16.dp))
                    MetricBlock(stringResource(R.string.metric_avg_pace), Formatters.paceValue(TrackMath.paceSecPerKm(avg), units), Formatters.paceUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                } else {
                    MetricBlock(stringResource(R.string.metric_speed), Formatters.speedValue(ctx, t.currentSpeed, units), Formatters.speedUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                    VDivider(Modifier.fillMaxHeight()); Spacer(Modifier.width(16.dp))
                    MetricBlock(stringResource(R.string.metric_avg), Formatters.speedValue(ctx, avg, units), Formatters.speedUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                }
                VDivider(Modifier.fillMaxHeight()); Spacer(Modifier.width(16.dp))
                if (s.showAltitude) {
                    MetricBlock(stringResource(R.string.metric_altitude), Formatters.altitudeValue(ctx, t.altitude, units), Formatters.altitudeUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                } else {
                    MetricBlock(stringResource(R.string.metric_max), Formatters.speedValue(ctx, t.maxSpeed, units), Formatters.speedUnit(ctx, units), Modifier.weight(1f), valueStyle = Num.m)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        LiveMap(t, s, Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().widthIn(max = ContentMaxWidth).align(Alignment.CenterHorizontally).padding(horizontal = ScreenPadding, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton(stringResource(if (paused) R.string.action_resume else R.string.action_pause), onPause, Modifier.weight(1f), enabled = t.phase != TrackingPhase.STARTING)
            PrimaryButton(stringResource(R.string.action_finish), onFinish, Modifier.weight(1f), enabled = t.phase != TrackingPhase.STARTING)
        }
    }
}

@Composable
private fun LiveMap(t: LiveTracking, s: UserSettingsEntity, modifier: Modifier) {
    val data = remember(t.route, t.current) {
        val route = t.route
        val markers = buildList {
            route.firstOrNull()?.let { add(MapMarker(it, MarkerKind.START)) }
            (t.current ?: route.lastOrNull())?.let { add(MapMarker(it, MarkerKind.CURRENT)) }
        }
        RouteMapData(listOf(route), markers)
    }
    Box(modifier.background(LocalMT.current.mapGround)) {
        if (t.route.isEmpty() && t.current == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(gpsLabel(t.gps), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.record_waiting_fix), style = MaterialTheme.typography.bodyMedium, color = LocalMT.current.ink2)
            }
        } else {
            RouteMap(data, Modifier.fillMaxSize(), interactive = true, showGrid = s.mapGrid)
        }
    }
}

/** Minimal driving UI: one huge number, glanceable limit state, hold-to-finish. */
@Composable
private fun VehicleRecord(t: LiveTracking, s: UserSettingsEntity, now: Long, onClose: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val units = s.units
    val limitMs = (t.speedLimitKmh ?: s.carSpeedThresholdKmh) / 3.6
    val over = t.currentSpeed > limitMs && t.phase == TrackingPhase.RECORDING
    val paused = t.phase == TrackingPhase.PAUSED || t.phase == TrackingPhase.AUTO_PAUSED
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = ScreenPadding)) {
        StatusLine(t, onClose)
        Spacer(Modifier.weight(0.6f))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val speed = Formatters.number(ctx, if (Formatters.imperial(units)) t.currentSpeed * 3600 / Formatters.M_PER_MILE else t.currentSpeed * 3.6, 0)
            val size = (maxWidth.value / (3 * 0.62f)).coerceAtMost(180f) / LocalDensity.current.fontScale
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    speed,
                    style = TextStyle(fontFamily = Num.hero.fontFamily, fontWeight = Num.hero.fontWeight, fontSize = size.sp, lineHeight = (size * 1.02f).sp),
                    color = if (over) mt.danger else mt.ink,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = Formatters.speed(ctx, t.currentSpeed, units) },
                )
                Text(Formatters.speedUnit(ctx, units), style = Num.m, color = mt.ink2)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, if (over) mt.danger else mt.line, Square)
                .background(if (over) mt.danger.copy(alpha = 0.10f) else Color.Transparent, Square).padding(14.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (over) { Icon(Icons.Filled.Warning, null, tint = mt.danger); Spacer(Modifier.width(10.dp)) }
            Text(
                stringResource(if (over) R.string.car_over_limit else R.string.car_limit, Formatters.speed(ctx, limitMs, units)),
                style = MaterialTheme.typography.titleMedium, color = if (over) mt.danger else mt.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            MetricBlock(stringResource(R.string.metric_distance), Formatters.distanceValue(ctx, t.distanceM, units), Formatters.distanceUnit(ctx, units), Modifier.weight(1f), Num.m)
            VDivider(Modifier.fillMaxHeight()); Spacer(Modifier.width(16.dp))
            MetricBlock(stringResource(R.string.metric_time), Formatters.shortClock(t.activeMs(now)), null, Modifier.weight(1f), Num.m)
            VDivider(Modifier.fillMaxHeight()); Spacer(Modifier.width(16.dp))
            MetricBlock(stringResource(R.string.metric_max), Formatters.speedValue(ctx, t.maxSpeed, units), Formatters.speedUnit(ctx, units), Modifier.weight(1f), Num.m)
        }
        Spacer(Modifier.weight(1f))
        Caption(stringResource(R.string.car_not_certified), modifier = Modifier.padding(bottom = 12.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(if (paused) R.string.action_resume else R.string.action_pause), onPause, Modifier.weight(1f))
            HoldToFinish(Modifier.weight(1f), onFinish = onStop, onAccessibleClick = { confirm = true })
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.record_finish_title),
            body = stringResource(R.string.record_finish_body),
            confirm = stringResource(R.string.action_finish),
            onConfirm = { confirm = false; onStop() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun HoldToFinish(modifier: Modifier, onFinish: () -> Unit, onAccessibleClick: () -> Unit) {
    val mt = LocalMT.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.car_hold_to_finish)
    Box(
        modifier
            .heightIn(min = 56.dp)
            .background(mt.accent, Square)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { onAccessibleClick(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(1500, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFinish()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(Color.Black.copy(alpha = 0.25f)))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
    }
}
