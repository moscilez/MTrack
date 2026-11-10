package com.moscilez.track.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.repo.toSpec
import com.moscilez.track.data.repo.toStat
import com.moscilez.track.domain.recommend.RecKind
import com.moscilez.track.domain.recommend.Recommendation
import com.moscilez.track.domain.recommend.RecommendationEngine
import com.moscilez.track.domain.recommend.Severity
import com.moscilez.track.domain.stats.GoalEvaluator
import com.moscilez.track.domain.stats.PersonalRecord
import com.moscilez.track.domain.stats.PersonalRecords
import com.moscilez.track.domain.stats.StatsAggregator
import com.moscilez.track.domain.stats.StatsPeriod
import com.moscilez.track.domain.stats.StatsReport
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.charts.BarChart
import com.moscilez.track.ui.components.AppHeader
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.ErrorState
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.MetricBlock
import com.moscilez.track.ui.components.ScreenTitle
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.VDivider
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToInt

data class StatsUi(
    val loading: Boolean = true,
    val error: Boolean = false,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val units: String = "METRIC",
    val empty: Boolean = false,
    val report: StatsReport? = null,
    val records: List<PersonalRecord> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(),
    val recommendationsEnabled: Boolean = true,
)

class StatsViewModel(private val c: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.WEEK)

    val ui: StateFlow<StatsUi> = combine(
        period, c.activityRepository.finished, c.goalRepository.active, c.profileRepository.settings,
    ) { p, finished, goals, settings ->
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val stats = finished.map { it.toStat() }
        val statuses = goals.map { GoalEvaluator.evaluate(it.toSpec(), stats, now, zone) }
        val muted = settings.mutedRecommendations.split(',').mapNotNull { k -> RecKind.entries.firstOrNull { it.name == k } }.toSet()
        StatsUi(
            loading = false,
            period = p,
            units = settings.units,
            empty = stats.isEmpty(),
            report = StatsAggregator.report(stats, p, now, zone),
            records = PersonalRecords.compute(stats).sortedByDescending { it.time }.take(8),
            recommendations = if (settings.recommendationsEnabled) RecommendationEngine.generate(stats, statuses, now, zone, muted) else emptyList(),
            recommendationsEnabled = settings.recommendationsEnabled,
        )
    }.flowOn(Dispatchers.Default)
        .catch { emit(StatsUi(loading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUi())

    fun setPeriod(p: StatsPeriod) { period.value = p }

    fun mute(kind: RecKind) {
        viewModelScope.launch {
            c.profileRepository.updateSettings { s ->
                val set = s.mutedRecommendations.split(',').filter { it.isNotBlank() }.toSet() + kind.name
                s.copy(mutedRecommendations = set.joinToString(","))
            }
        }
    }
}

private fun periodLabel(p: StatsPeriod): Int = when (p) {
    StatsPeriod.DAY -> R.string.period_day
    StatsPeriod.WEEK -> R.string.period_week
    StatsPeriod.MONTH -> R.string.period_month
    StatsPeriod.YEAR -> R.string.period_year
    StatsPeriod.ALL -> R.string.period_all
}

@Composable
fun StatsScreen(onOpenActivity: (Long) -> Unit, onSettings: () -> Unit, onProfile: () -> Unit) {
    val vm = appViewModel { StatsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    var why by remember { mutableStateOf<Recommendation?>(null) }

    BoundedLazyColumn(Modifier.statusBarsPadding()) {
        item { AppHeader(onSettings, onProfile) }
        item { ScreenTitle(stringResource(R.string.stats_title)) }
        item {
            Segmented(StatsPeriod.entries, ui.period, { stringResource(periodLabel(it)) }, vm::setPeriod, Modifier.padding(top = 8.dp))
        }
        if (ui.loading) { item { LoadingState() }; return@BoundedLazyColumn }
        if (ui.error) { item { ErrorState(stringResource(R.string.stats_error)) }; return@BoundedLazyColumn }
        val r = ui.report
        if (ui.empty || r == null) {
            item { EmptyState(stringResource(R.string.stats_empty_title), stringResource(R.string.stats_empty_body)) }
            return@BoundedLazyColumn
        }
        val t = r.totals
        // Hero: distance and change against the previous period.
        item {
            Column(Modifier.padding(top = 24.dp).semantics(mergeDescendants = true) {}) {
                Caption(stringResource(R.string.stats_distance))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Formatters.distanceValue(ctx, t.distanceM, ui.units), style = Num.xl, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(Formatters.distanceUnit(ctx, ui.units), style = Num.m, modifier = Modifier.padding(bottom = 6.dp))
                }
                val prev = r.previous
                if (prev != null && prev.distanceM > 0) {
                    val pct = ((t.distanceM - prev.distanceM) / prev.distanceM * 100).roundToInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (pct >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null,
                            tint = if (pct >= 0) mt.accent else mt.ink2,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.stats_vs_previous, (if (pct > 0) "+" else "") + pct), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                    }
                } else if (prev != null) {
                    Text(stringResource(R.string.stats_no_previous), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                }
            }
        }
        item {
            Row(Modifier.padding(top = 24.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
                MetricBlock(stringResource(R.string.metric_time), Formatters.duration(ctx, t.durationMs), null, Modifier.weight(1f), valueStyle = Num.s)
                VDivider(Modifier.fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                MetricBlock(stringResource(R.string.stats_workouts), t.count.toString(), null, Modifier.weight(1f), valueStyle = Num.s)
                VDivider(Modifier.fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                MetricBlock(stringResource(R.string.stats_streak), pluralStringResource(R.plurals.days_count, r.streakDays, r.streakDays), null, Modifier.weight(1f), valueStyle = Num.s)
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp)) {
                KeyValueRow(stringResource(R.string.metric_avg_speed), Formatters.speed(ctx, t.avgSpeed, ui.units))
                KeyValueRow(stringResource(R.string.metric_max), Formatters.speed(ctx, t.maxSpeed, ui.units))
                if (t.avgPaceSecPerKm != null) KeyValueRow(stringResource(R.string.metric_avg_pace), Formatters.pace(ctx, t.avgPaceSecPerKm, ui.units))
                KeyValueRow(stringResource(R.string.metric_elevation_gain), Formatters.altitude(ctx, t.elevationGain, ui.units))
                KeyValueRow(stringResource(R.string.metric_moving_time), Formatters.duration(ctx, t.movingMs))
                if (t.calories > 0) KeyValueRow(stringResource(R.string.metric_calories), stringResource(R.string.unit_kcal_value, t.calories.roundToInt()))
            }
        }
        // Trend
        item {
            SectionHeader(stringResource(R.string.stats_trend))
            val zone = ZoneId.systemDefault()
            val labels = r.trend.map { b ->
                val d = Instant.ofEpochMilli(b.start).atZone(zone)
                when (ui.period) {
                    StatsPeriod.DAY, StatsPeriod.WEEK -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Formatters.locale(ctx))
                    StatsPeriod.MONTH -> d.dayOfMonth.toString()
                    else -> d.month.getDisplayName(TextStyle.NARROW, Formatters.locale(ctx))
                }
            }
            val values = r.trend.map { (if (Formatters.imperial(ui.units)) it.distanceM / Formatters.M_PER_MILE else it.distanceM / 1000.0).toFloat() }
            BarChart(values, labels, stringResource(R.string.cd_chart_trend))
        }
        item {
            SectionHeader(stringResource(R.string.stats_weekdays))
            val loc = Formatters.locale(ctx)
            BarChart(
                DayOfWeek.entries.map { ((r.byWeekday[it] ?: 0.0) / 60_000.0).toFloat() },
                DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, loc) },
                stringResource(R.string.cd_chart_weekdays),
                highlightIndex = -1,
            )
        }
        if (r.byType.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.stats_by_type)) }
            val totalMs = r.byType.sumOf { it.durationMs }.coerceAtLeast(1)
            items(r.byType, key = { "type-" + it.type.name }) { s ->
                Column(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.type.icon(), null, Modifier.padding(end = 12.dp))
                        Text(stringResource(s.type.label()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            pluralStringResource(R.plurals.sessions_count, s.count, s.count) + " · " + Formatters.distance(ctx, s.distanceM, ui.units),
                            style = Num.s.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = mt.ink2,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    FlatProgress(s.durationMs.toFloat() / totalMs, color = mt.ink)
                }
            }
        }
        val v = r.vehicle
        if (v.trips > 0) {
            item {
                SectionHeader(stringResource(R.string.stats_vehicle))
                KeyValueRow(stringResource(R.string.stats_trips), v.trips.toString())
                KeyValueRow(stringResource(R.string.metric_distance), Formatters.distance(ctx, v.distanceM, ui.units))
                KeyValueRow(stringResource(R.string.stats_driving_time), Formatters.duration(ctx, v.drivingMs))
                KeyValueRow(stringResource(R.string.metric_stops), v.stops.toString())
                KeyValueRow(stringResource(R.string.metric_max), Formatters.speed(ctx, v.maxSpeed, ui.units))
                if (v.overSpeedMs > 0) KeyValueRow(stringResource(R.string.metric_over_speed), Formatters.duration(ctx, v.overSpeedMs), valueColor = mt.danger)
                v.fuelLiters?.let { KeyValueRow(stringResource(R.string.metric_fuel), stringResource(R.string.unit_liters_value, Formatters.number(ctx, it, 1))) }
            }
        }
        if (ui.records.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.stats_records)) }
            items(ui.records, key = { "rec-" + it.type.name + it.kind.name }) { rec ->
                ListRow(
                    title = stringResource(Texts.recordLabel(rec.kind)) + " — " + Texts.recordValue(ctx, rec.kind, rec.value, ui.units),
                    subtitle = stringResource(rec.type.label()) + " · " + Formatters.date(ctx, rec.time),
                    icon = rec.type.icon(),
                    onClick = { onOpenActivity(rec.activityId) },
                )
            }
        }
        item { SectionHeader(stringResource(R.string.stats_recommendations)) }
        if (!ui.recommendationsEnabled) {
            item { Text(stringResource(R.string.stats_recs_disabled), style = MaterialTheme.typography.bodyMedium, color = mt.ink2) }
        } else if (ui.recommendations.isEmpty()) {
            item { Text(stringResource(R.string.stats_recs_empty), style = MaterialTheme.typography.bodyMedium, color = mt.ink2) }
        } else {
            items(ui.recommendations, key = { "r-" + it.kind.name + (it.type?.name ?: "") + (it.goalTitle ?: "") }) { rec ->
                ListRow(
                    title = Texts.recommendation(ctx, rec, ui.units),
                    subtitle = stringResource(R.string.stats_why),
                    icon = when (rec.severity) {
                        Severity.ATTENTION -> Icons.Outlined.WarningAmber
                        Severity.POSITIVE -> Icons.Outlined.Lightbulb
                        Severity.INFO -> Icons.Outlined.Info
                    },
                    onClick = { why = rec },
                )
            }
            item { Caption(stringResource(R.string.stats_recs_disclaimer), Modifier.padding(top = 12.dp)) }
        }
    }

    why?.let { rec ->
        ConfirmDialog(
            title = stringResource(R.string.stats_why_title),
            body = Texts.recommendation(ctx, rec, ui.units) + "\n\n" + stringResource(Texts.recommendationWhy(rec.kind)),
            confirm = stringResource(R.string.stats_mute),
            onConfirm = { vm.mute(rec.kind); why = null },
            onDismiss = { why = null },
        )
    }
}
