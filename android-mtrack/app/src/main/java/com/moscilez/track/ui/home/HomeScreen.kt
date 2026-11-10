package com.moscilez.track.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.repo.toSpec
import com.moscilez.track.data.repo.toStat
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.domain.recommend.RecKind
import com.moscilez.track.domain.recommend.Recommendation
import com.moscilez.track.domain.recommend.RecommendationEngine
import com.moscilez.track.domain.stats.GoalEvaluator
import com.moscilez.track.domain.stats.Periods
import com.moscilez.track.domain.stats.StatsPeriod
import com.moscilez.track.tracking.LiveTracking
import com.moscilez.track.tracking.TrackingService
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.AppHeader
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.VDivider
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.components.rememberNow
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class HomeUi(
    val loading: Boolean = true,
    val name: String = "",
    val units: String = "METRIC",
    val weekDistanceM: Double = 0.0,
    val weekDurationMs: Long = 0,
    val goal: GoalStatus? = null,
    val recent: List<ActivitySessionEntity> = emptyList(),
    val recommendation: Recommendation? = null,
    val interrupted: ActivitySessionEntity? = null,
    val tracking: LiveTracking = LiveTracking(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val ui: StateFlow<HomeUi> = combine(
        c.profileRepository.settings,
        c.profileRepository.profile,
        c.activityRepository.finished,
        c.goalRepository.active,
        c.activityRepository.unfinished,
    ) { settings, profile, finished, goals, unfinished ->
        val now = System.currentTimeMillis()
        val stats = finished.map { it.toStat() }
        val week = Periods.range(StatsPeriod.WEEK, now, zone)
        val weekItems = stats.filter { it.startTime in week && !it.type.isVehicle }
        val statuses = goals.map { GoalEvaluator.evaluate(it.toSpec(), stats, now, zone) }
        val goal = statuses.firstOrNull { it.goal.type == GoalType.PERIOD_DISTANCE && it.goal.period == GoalPeriod.WEEK }
            ?: statuses.firstOrNull()
        val muted = settings.mutedRecommendations.split(',').mapNotNull { k -> RecKind.entries.firstOrNull { it.name == k } }.toSet()
        val rec = if (settings.recommendationsEnabled) RecommendationEngine.generate(stats, statuses, now, zone, muted).firstOrNull() else null
        HomeUi(
            loading = false,
            name = profile?.name.orEmpty(),
            units = settings.units,
            weekDistanceM = weekItems.sumOf { it.distanceM },
            weekDurationMs = weekItems.sumOf { it.durationMs },
            goal = goal,
            recent = finished.sortedByDescending { it.startTime }.take(3),
            recommendation = rec,
            interrupted = unfinished,
        )
    }.flowOn(Dispatchers.Default)
        .combine(c.trackingState.state) { ui, t -> ui.copy(tracking = t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())

    /** Finalizes a recording interrupted by a crash or reboot without resuming GPS. */
    fun finishInterrupted(session: ActivitySessionEntity) {
        viewModelScope.launch {
            val repo = c.activityRepository
            val last = repo.lastPoint(session.id)
            val active = maxOf(session.accumulatedActiveMs, last?.elapsedMs ?: 0)
            repo.update(session.copy(endTime = last?.timestamp ?: System.currentTimeMillis()))
            val settings = c.profileRepository.getSettings()
            repo.finalize(session.id, active, c.profileRepository.getProfile()?.weightKg, settings.carSpeedThresholdKmh, settings.carFuelLPer100)
        }
    }

    fun discardInterrupted(session: ActivitySessionEntity) {
        viewModelScope.launch { c.activityRepository.delete(listOf(session.id)) }
    }
}

fun greeting(context: Context, hour: Int, name: String): String {
    val base = context.getString(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_day
            in 18..22 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }
    )
    return if (name.isBlank()) base else "$base, ${name.trim()}"
}

fun sessionLine(context: Context, s: ActivitySessionEntity, units: String): String {
    val type = context.getString(ActivityType.from(s.type).label())
    return "$type · ${Formatters.distance(context, s.distanceM, units)} · ${Formatters.shortClock(s.durationMs)}"
}

@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onOpenRecord: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val now by rememberNow(1000)
    var confirmDiscard by remember { mutableStateOf<ActivitySessionEntity?>(null) }

    BoundedLazyColumn(Modifier.statusBarsPadding()) {
        item { AppHeader(onSettings, onProfile) }
        if (ui.loading) {
            item { LoadingState() }
            return@BoundedLazyColumn
        }
        item {
            val hour = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
            Column(Modifier.padding(top = 28.dp)) {
                Text(greeting(ctx, hour, ui.name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(Formatters.longDay(ctx, now), style = MaterialTheme.typography.bodyLarge, color = mt.ink2)
            }
        }
        // Live or interrupted recording banners come first: they are the most urgent state.
        val t = ui.tracking
        if (t.isActive) {
            item {
                Row(
                    Modifier.padding(top = 20.dp).fillMaxWidth().heightIn(min = 56.dp).border(1.dp, mt.accent, Square)
                        .background(mt.accentSoft, Square).clickable(role = Role.Button, onClick = onOpenRecord).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(mt.accent, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_recording_now, stringResource(t.type.label())), style = MaterialTheme.typography.titleSmall, color = mt.accent)
                        Text(
                            Formatters.clock(t.activeMs(now)) + " · " + Formatters.distance(ctx, t.distanceM, ui.units),
                            style = Num.s, color = mt.ink,
                        )
                    }
                    Text(stringResource(R.string.action_open), color = mt.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else ui.interrupted?.let { s ->
            item {
                Column(Modifier.padding(top = 20.dp).fillMaxWidth().border(1.dp, mt.ink, Square).padding(16.dp)) {
                    Text(stringResource(R.string.home_interrupted_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.home_interrupted_body, sessionLine(ctx, s, ui.units)),
                        style = MaterialTheme.typography.bodyMedium, color = mt.ink2,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.action_finish), { vm.finishInterrupted(s) }, Modifier.weight(1f))
                        PrimaryButton(stringResource(R.string.action_continue), {
                            TrackingService.command(ctx, TrackingService.ACTION_RESTORE)
                            onOpenRecord()
                        }, Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.action_discard),
                        color = mt.danger,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).clickable(role = Role.Button) { confirmDiscard = s }.padding(top = 14.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(top = 28.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Caption(stringResource(R.string.home_week_distance))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Formatters.distanceValue(ctx, ui.weekDistanceM, ui.units), style = Num.l, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(Formatters.distanceUnit(ctx, ui.units), style = Num.m, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Text(stringResource(R.string.home_this_week), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                }
                VDivider(Modifier.fillMaxHeight().padding(horizontal = 0.dp))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Caption(stringResource(R.string.home_week_time))
                    Spacer(Modifier.height(8.dp))
                    Text(Formatters.duration(ctx, ui.weekDurationMs), style = Num.m, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    val g = ui.goal
                    if (g != null) {
                        val pct = (g.fraction * 100).toInt()
                        val target = Texts.goalValue(ctx, g.goal.type, g.goal.target, ui.units)
                        Text(stringResource(R.string.home_goal_progress, target, pct), style = MaterialTheme.typography.bodyMedium, color = mt.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        FlatProgress(g.fraction.toFloat(), description = stringResource(R.string.home_goal_progress, target, pct))
                    } else {
                        Text(stringResource(R.string.home_no_goal), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(28.dp))
            PrimaryButton(
                stringResource(if (t.isActive) R.string.home_back_to_recording else R.string.home_start),
                if (t.isActive) onOpenRecord else onStart,
            )
        }
        item { SectionHeader(stringResource(R.string.home_recent), action = stringResource(R.string.action_all), onAction = onSeeAll) }
        if (ui.recent.isEmpty()) {
            item { EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body)) }
        } else {
            items(ui.recent, key = { it.id }) { s ->
                ListRow(
                    title = sessionLine(ctx, s, ui.units),
                    subtitle = Formatters.relativeDay(ctx, s.startTime, now),
                    icon = ActivityType.from(s.type).icon(),
                    onClick = { onOpenActivity(s.id) },
                )
            }
        }
        ui.recommendation?.let { r ->
            item {
                ListRow(
                    title = stringResource(R.string.home_rec_prefix, Texts.recommendation(ctx, r, ui.units)),
                    icon = Icons.Filled.BarChart,
                    divider = false,
                    onClick = onStats,
                )
            }
        }
    }

    confirmDiscard?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.home_discard_title),
            body = stringResource(R.string.home_discard_body),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { vm.discardInterrupted(s); confirmDiscard = null },
            onDismiss = { confirmDiscard = null },
        )
    }
}
