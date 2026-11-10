package com.moscilez.track.ui.details

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.ActivitySegmentEntity
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.ActivityStatisticEntity
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.data.repo.ActivityRepository
import com.moscilez.track.data.repo.toStat
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.PeakKind
import com.moscilez.track.domain.model.SegmentKind
import com.moscilez.track.domain.model.TripType
import com.moscilez.track.domain.stats.PersonalRecord
import com.moscilez.track.domain.stats.PersonalRecords
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.tracking.LatLon
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.charts.LineChart
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.ErrorState
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.map.MapMarker
import com.moscilez.track.ui.map.MarkerKind
import com.moscilez.track.ui.map.RouteMap
import com.moscilez.track.ui.map.RouteMapData
import com.moscilez.track.ui.start.tripLabel
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.transfer.TransferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Derived, display-ready route data; computed once off the main thread. */
data class RouteAnalysis(
    val map: RouteMapData,
    val speedByDistance: List<Pair<Float, Float>>,
    val altitudeByDistance: List<Pair<Float, Float>>,
    val hasAltitude: Boolean,
)

data class DetailExtras(
    val route: RouteAnalysis? = null,
    val splits: List<ActivitySegmentEntity> = emptyList(),
    val stops: List<ActivitySegmentEntity> = emptyList(),
    val peaks: List<ActivityStatisticEntity> = emptyList(),
    val records: List<PersonalRecord> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

class DetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val repo = c.activityRepository
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    val session: StateFlow<ActivitySessionEntity?> = repo.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings: StateFlow<UserSettingsEntity> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettingsEntity())
    private val _extras = MutableStateFlow(DetailExtras())
    val extras: StateFlow<DetailExtras> = _extras

    init { load() }

    fun load() {
        _extras.value = DetailExtras(loading = true)
        viewModelScope.launch {
            try {
                val s = repo.get(id)
                _loaded.value = true
                if (s == null) { _extras.value = DetailExtras(loading = false); return@launch }
                val segs = repo.segments(id)
                val peaks = repo.statistics(id)
                val route = withContext(Dispatchers.Default) { analyze(s) }
                val records = withContext(Dispatchers.Default) {
                    PersonalRecords.recordsSetBy(id, repo.getAllFinished().map { it.toStat() })
                }
                _extras.value = DetailExtras(
                    route = route,
                    splits = segs.filter { it.kind == SegmentKind.SPLIT.name }.sortedBy { it.idx },
                    stops = segs.filter { it.kind == SegmentKind.STOP.name }.sortedBy { it.startElapsedMs },
                    peaks = peaks,
                    records = records,
                    loading = false,
                )
            } catch (e: Exception) {
                _loaded.value = true
                _extras.value = DetailExtras(loading = false, error = true)
            }
        }
    }

    private suspend fun analyze(s: ActivitySessionEntity): RouteAnalysis? {
        val dist = ArrayList<Double>()
        val speed = ArrayList<Double?>()
        val alt = ArrayList<Double?>()
        val segments = ArrayList<ArrayList<LatLon>>()
        var lastSeg = -1
        var prev: com.moscilez.track.data.db.ActivityPointEntity? = null
        var cum = 0.0
        val stride = ((s.pointCount + 2999) / 3000).coerceAtLeast(1)
        var i = 0
        repo.forEachPoint(id) { p ->
            if (!ActivityRepository.isValid(p)) return@forEachPoint
            val pv = prev
            if (pv != null && pv.segment == p.segment) {
                val d = TrackMath.distance(pv.latitude, pv.longitude, p.latitude, p.longitude)
                cum += d
                val dt = (p.timestamp - pv.timestamp) / 1000.0
                val sp = p.speed?.toDouble() ?: if (dt > 0) d / dt else 0.0
                speed += sp
            } else speed += (p.speed?.toDouble() ?: 0.0)
            dist += cum
            alt += p.altitude
            if (p.segment != lastSeg) { segments.add(ArrayList()); lastSeg = p.segment }
            if (i % stride == 0) segments.last() += LatLon(p.latitude, p.longitude)
            i++
            prev = p
        }
        if (dist.isEmpty()) return null
        prev?.let { segments.last() += LatLon(it.latitude, it.longitude) }
        val markers = buildList {
            segments.firstOrNull()?.firstOrNull()?.let { add(MapMarker(it, MarkerKind.START)) }
            segments.lastOrNull()?.lastOrNull()?.let { add(MapMarker(it, MarkerKind.END)) }
        }
        val stops = repo.segments(id).filter { it.kind == SegmentKind.STOP.name && it.latitude != null && it.longitude != null }
            .map { MapMarker(LatLon(it.latitude!!, it.longitude!!), MarkerKind.STOP) }
        val peaks = repo.statistics(id).filter { it.kind == PeakKind.MAX_SPEED.name || it.kind == PeakKind.MAX_ALTITUDE.name }
            .map { MapMarker(LatLon(it.latitude, it.longitude), MarkerKind.PEAK) }
        return RouteAnalysis(
            map = RouteMapData(segments, markers + stops + peaks),
            speedByDistance = bucket(dist, speed),
            altitudeByDistance = bucket(dist, alt),
            hasAltitude = alt.count { it != null } > 2,
        )
    }

    /** Averages values into at most 300 distance buckets so charts stay light. */
    private fun bucket(dist: List<Double>, values: List<Double?>, n: Int = 300): List<Pair<Float, Float>> {
        val total = dist.lastOrNull() ?: return emptyList()
        if (total <= 0) return emptyList()
        val sums = DoubleArray(n)
        val counts = IntArray(n)
        for (k in dist.indices) {
            val v = values[k] ?: continue
            val b = ((dist[k] / total) * (n - 1)).toInt().coerceIn(0, n - 1)
            sums[b] += v; counts[b]++
        }
        return (0 until n).filter { counts[it] > 0 }.map { b -> (total * b / (n - 1)).toFloat() to (sums[b] / counts[b]).toFloat() }
    }

    fun saveMeta(title: String, note: String, tags: String, trip: String?) = viewModelScope.launch {
        repo.updateMeta(id, title, note, tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","), trip)
    }

    fun delete(done: () -> Unit) = viewModelScope.launch { repo.delete(listOf(id)); done() }

    fun exportGpx(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = try { c.transferRepository.exportGpx(uri, id); true } catch (e: TransferException) { false } catch (e: Exception) { false }
        onResult(ok)
    }
}

private fun peakLabel(kind: String): Int = when (kind) {
    PeakKind.MAX_SPEED.name -> R.string.peak_max_speed
    PeakKind.MAX_ALTITUDE.name -> R.string.peak_max_altitude
    PeakKind.MIN_ALTITUDE.name -> R.string.peak_min_altitude
    PeakKind.MAX_CLIMB.name -> R.string.peak_max_climb
    PeakKind.MAX_DESCENT.name -> R.string.peak_max_descent
    PeakKind.MAX_ACCEL.name -> R.string.peak_max_accel
    else -> R.string.peak_max_decel
}

private fun peakValue(ctx: Context, s: ActivityStatisticEntity, units: String): String = when (s.kind) {
    PeakKind.MAX_SPEED.name -> Formatters.speed(ctx, s.value, units)
    PeakKind.MAX_ALTITUDE.name, PeakKind.MIN_ALTITUDE.name -> Formatters.altitude(ctx, s.value, units)
    PeakKind.MAX_CLIMB.name, PeakKind.MAX_DESCENT.name -> Formatters.number(ctx, s.value, 1) + "\u00A0%"
    else -> Formatters.number(ctx, s.value, 1) + "\u00A0" + ctx.getString(R.string.unit_accel)
}

@Composable
fun ActivityDetailScreen(id: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "detail-$id") { DetailViewModel(it, id) }
    val session by vm.session.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var fullMap by rememberSaveable { mutableStateOf(false) }
    val gpxDone = stringResource(R.string.gpx_saved)
    val gpxFail = stringResource(R.string.gpx_failed)
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) vm.exportGpx(uri) { ok -> Toast.makeText(ctx, if (ok) gpxDone else gpxFail, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        containerColor = mt.bg,
        topBar = {
            DetailTopBar(stringResource(R.string.detail_title), onBack) {
                if (session != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = Square, containerColor = mt.surface) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; editing = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_export_gpx)) },
                                enabled = (session?.pointCount ?: 0) > 0,
                                onClick = {
                                    menu = false
                                    val s = session ?: return@DropdownMenuItem
                                    gpxLauncher.launch("mtrack-" + Formatters.date(ctx, s.startTime, "YMD") + ".gpx")
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = mt.danger) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            }
        },
    ) { inner ->
        val s = session
        when {
            !loaded -> LoadingState(Modifier.padding(inner))
            extras.error -> Box(Modifier.padding(inner).padding(horizontal = 24.dp)) { ErrorState(stringResource(R.string.detail_error), onRetry = vm::load) }
            s == null -> Box(Modifier.padding(inner).padding(horizontal = 24.dp)) {
                EmptyState(stringResource(R.string.detail_missing_title), stringResource(R.string.detail_missing_body), action = stringResource(R.string.action_back), onAction = onBack)
            }
            else -> DetailContent(s, extras, settings, Modifier.padding(inner), onOpenMap = { fullMap = true })
        }
    }

    val s = session
    if (editing && s != null) EditMetaDialog(s, onDismiss = { editing = false }) { title, note, tags, trip ->
        vm.saveMeta(title, note, tags, trip); editing = false
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_title),
            body = stringResource(R.string.detail_delete_body),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
    val route = extras.route
    if (fullMap && route != null) {
        Dialog(onDismissRequest = { fullMap = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(mt.mapGround)) {
                RouteMap(route.map, Modifier.fillMaxSize(), interactive = true, showGrid = settings.mapGrid)
                IconButton(
                    onClick = { fullMap = false },
                    modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd).background(mt.surface, Square),
                ) { Icon(Icons.Filled.Close, stringResource(R.string.cd_close)) }
            }
        }
    }
}

@Composable
private fun DetailContent(s: ActivitySessionEntity, x: DetailExtras, st: UserSettingsEntity, modifier: Modifier, onOpenMap: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val units = st.units
    val type = ActivityType.from(s.type)
    BoundedLazyColumn(modifier) {
        item {
            Column(Modifier.padding(top = 4.dp, bottom = 16.dp)) {
                Text(s.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(
                    Formatters.date(ctx, s.startTime, st.dateFormat) + " · " + Formatters.time(ctx, s.startTime, st.use24h) + " · " + stringResource(type.label()),
                    style = MaterialTheme.typography.bodyLarge, color = mt.ink2,
                )
            }
        }
        item {
            val route = x.route
            Box(
                Modifier.fillMaxWidth().height(220.dp).border(1.dp, mt.line, Square).background(mt.mapGround, Square)
                    .then(if (route != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_open_map), onClick = onOpenMap) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    x.loading -> LoadingState()
                    route == null -> Text(stringResource(R.string.detail_no_route), color = mt.ink2, style = MaterialTheme.typography.bodyMedium)
                    else -> RouteMap(route.map, Modifier.fillMaxSize(), interactive = false, showGrid = st.mapGrid)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            KeyValueRow(stringResource(R.string.metric_distance), Formatters.distance(ctx, s.distanceM, units), Icons.Filled.Route)
            KeyValueRow(stringResource(R.string.metric_time), Formatters.duration(ctx, s.durationMs), Icons.Filled.Schedule)
            KeyValueRow(stringResource(R.string.metric_moving_time), Formatters.duration(ctx, s.movingMs), Icons.Filled.Timer)
            if (type.showsPace && s.avgPaceSecPerKm != null) {
                KeyValueRow(stringResource(R.string.metric_avg_pace), Formatters.pace(ctx, s.avgPaceSecPerKm, units), Icons.Filled.Speed)
            }
            KeyValueRow(stringResource(R.string.metric_avg_speed), Formatters.speed(ctx, s.avgSpeed, units), Icons.Filled.Speed)
            KeyValueRow(stringResource(R.string.metric_max), Formatters.speed(ctx, s.maxSpeed, units), Icons.Filled.BarChart)
            if (st.showAltitude) KeyValueRow(stringResource(R.string.metric_elevation_gain), Formatters.altitude(ctx, s.elevationGain, units), Icons.Filled.Terrain)
            if (st.showCalories && s.calories != null && !type.isVehicle) {
                KeyValueRow(stringResource(R.string.metric_calories), stringResource(R.string.unit_kcal_value, s.calories.toInt()), Icons.Filled.LocalFireDepartment)
            }
            KeyValueRow(stringResource(R.string.metric_stops), s.stopsCount.toString() + " · " + Formatters.duration(ctx, s.stoppedMs), Icons.Filled.PauseCircle)
            if (type.isVehicle) {
                if (s.overSpeedMs > 0) KeyValueRow(stringResource(R.string.metric_over_speed), Formatters.duration(ctx, s.overSpeedMs), Icons.Filled.Warning, mt.danger)
                s.fuelLiters?.let { KeyValueRow(stringResource(R.string.metric_fuel), stringResource(R.string.unit_liters_value, Formatters.number(ctx, it, 1)), Icons.Filled.LocalGasStation) }
                s.tripType?.let { tt -> runCatching { TripType.valueOf(tt) }.getOrNull()?.let { KeyValueRow(stringResource(R.string.start_trip_type), stringResource(tripLabel(it))) } }
            }
        }
        val route = x.route
        if (route != null && route.speedByDistance.size > 2) {
            val distDiv = if (Formatters.imperial(units)) Formatters.M_PER_MILE else 1000.0
            val distUnit = Formatters.distanceUnit(ctx, units)
            val usePace = type.showsPace && st.showPace
            item {
                Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (usePace) R.string.chart_pace else R.string.chart_speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    Text(if (usePace) Formatters.paceUnit(ctx, units) else Formatters.speedUnit(ctx, units), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                }
                val pts = route.speedByDistance.map { (d, v) ->
                    val xv = (d / distDiv).toFloat()
                    val yv = if (usePace) {
                        (TrackMath.paceSecPerKm(v.toDouble())?.let { p -> if (Formatters.imperial(units)) p * Formatters.M_PER_MILE / 1000 else p } ?: 0.0).toFloat() / 60f
                    } else {
                        (if (Formatters.imperial(units)) v * 3600 / Formatters.M_PER_MILE else v * 3.6).toFloat()
                    }
                    xv to yv.coerceAtMost(if (usePace) 30f else 400f)
                }
                LineChart(
                    pts,
                    description = stringResource(R.string.cd_chart_speed),
                    formatX = { Formatters.number(ctx, it.toDouble(), if (it < 10) 1 else 0) },
                    formatY = { it.toInt().toString() },
                )
                Text(distUnit, style = MaterialTheme.typography.bodySmall, color = mt.ink2, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (route.hasAltitude && st.showAltitude) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chart_altitude), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        Text(Formatters.altitudeUnit(ctx, units), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                    }
                    LineChart(
                        route.altitudeByDistance.map { (d, a) -> (d / distDiv).toFloat() to (if (Formatters.imperial(units)) a * Formatters.FT_PER_M.toFloat() else a) },
                        description = stringResource(R.string.cd_chart_altitude),
                        formatX = { Formatters.number(ctx, it.toDouble(), if (it < 10) 1 else 0) },
                        fixedMinY = null,
                    )
                }
            }
        }
        if (x.records.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.detail_records)) }
            items(x.records, key = { "r" + it.kind.name }) { r ->
                ListRow(
                    title = stringResource(Texts.recordLabel(r.kind)),
                    subtitle = Texts.recordValue(ctx, r.kind, r.value, units),
                    icon = Icons.Filled.EmojiEvents,
                    chevron = false,
                )
            }
        }
        if (x.splits.isNotEmpty()) {
            item { SectionHeader(stringResource(if (Formatters.imperial(units)) R.string.detail_splits_mi else R.string.detail_splits_km)) }
            items(x.splits, key = { "s" + it.id }) { sp ->
                val full = sp.distanceM >= 990
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (full) "${sp.idx + 1}\u00A0" + Formatters.distanceUnit(ctx, "METRIC") else Formatters.distance(ctx, sp.distanceM, "METRIC"),
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        )
                        if (st.showAltitude && kotlin.math.abs(sp.elevationDelta) >= 1) {
                            Text(
                                (if (sp.elevationDelta > 0) "+" else "−") + Formatters.altitude(ctx, kotlin.math.abs(sp.elevationDelta), units),
                                style = MaterialTheme.typography.bodySmall, color = mt.ink2, modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                        Text(Formatters.shortClock(sp.endElapsedMs), style = com.moscilez.track.ui.theme.Num.s)
                    }
                    com.moscilez.track.ui.components.Hairline()
                }
            }
        }
        if (x.stops.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.detail_stops)) }
            items(x.stops, key = { "p" + it.id }) { stop ->
                KeyValueRow(
                    stringResource(R.string.detail_stop_at, Formatters.time(ctx, s.startTime + stop.startElapsedMs, st.use24h)),
                    Formatters.duration(ctx, stop.endElapsedMs - stop.startElapsedMs),
                )
            }
        }
        if (x.peaks.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.detail_peaks)) }
            items(x.peaks, key = { "k" + it.id }) { pk ->
                KeyValueRow(stringResource(peakLabel(pk.kind)), peakValue(ctx, pk, units))
            }
        }
        if (s.note.isNotBlank() || s.tags.isNotBlank()) {
            item {
                SectionHeader(stringResource(R.string.detail_notes))
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodyLarge)
                if (s.tags.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Caption(s.tags.split(',').joinToString("  ") { "#" + it.trim() })
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun EditMetaDialog(s: ActivitySessionEntity, onDismiss: () -> Unit, onSave: (String, String, String, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(s.title) }
    var note by rememberSaveable { mutableStateOf(s.note) }
    var tags by rememberSaveable { mutableStateOf(s.tags) }
    var trip by rememberSaveable { mutableStateOf(s.tripType) }
    val type = ActivityType.from(s.type)
    ConfirmDialog(
        title = stringResource(R.string.detail_edit_title),
        body = "",
        confirm = stringResource(R.string.action_save),
        confirmEnabled = title.isNotBlank(),
        onConfirm = { onSave(title.take(120), note.take(2000), tags.take(300), trip) },
        onDismiss = onDismiss,
        extra = {
            MTTextField(title, { title = it }, stringResource(R.string.field_title))
            MTTextField(note, { note = it }, stringResource(R.string.field_note), singleLine = false, minLines = 3)
            MTTextField(tags, { tags = it }, stringResource(R.string.field_tags), supporting = stringResource(R.string.field_tags_hint))
            if (type.isVehicle) {
                com.moscilez.track.ui.components.Segmented(
                    TripType.entries.toList(), runCatching { TripType.valueOf(trip ?: "") }.getOrDefault(TripType.PERSONAL),
                    label = { stringResource(tripLabel(it)) }, onSelect = { trip = it.name },
                )
            }
        },
    )
}
