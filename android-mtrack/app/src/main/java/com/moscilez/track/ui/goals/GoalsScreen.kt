package com.moscilez.track.ui.goals

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.data.repo.toSpec
import com.moscilez.track.data.repo.toStat
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.AppHeader
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.ErrorState
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.Hairline
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenTitle
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

data class GoalsUi(
    val loading: Boolean = true,
    val error: Boolean = false,
    val active: List<GoalStatus> = emptyList(),
    val archived: List<GoalEntity> = emptyList(),
    val units: String = "METRIC",
)

class GoalsViewModel(private val c: AppContainer) : ViewModel() {
    val ui: StateFlow<GoalsUi> = combine(
        c.goalRepository.all, c.activityRepository.finished, c.profileRepository.settings,
    ) { goals, finished, settings ->
        val stats = finished.map { it.toStat() }
        val active = goals.filter { !it.archived }
        // Persisting progress builds each goal's per-period history.
        val statuses = c.goalRepository.evaluateAndRecord(active, stats, System.currentTimeMillis(), ZoneId.systemDefault())
        GoalsUi(loading = false, active = statuses, archived = goals.filter { it.archived }, units = settings.units)
    }.catch { emit(GoalsUi(loading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUi())
}

/** "3 days left" / "ends today" / "no deadline" line for a goal status. */
fun goalTimeLine(ctx: Context, s: GoalStatus): String = when {
    s.daysLeft < 0 -> ctx.getString(R.string.goal_no_deadline)
    s.daysLeft == 0 -> ctx.getString(R.string.goal_ends_today)
    else -> ctx.resources.getQuantityString(R.plurals.goal_days_left, s.daysLeft, s.daysLeft)
}

fun goalTitle(ctx: Context, s: GoalStatus): String = s.goal.title.ifBlank { ctx.getString(Texts.goalTypeLabel(s.goal.type)) }

@Composable
fun GoalRow(s: GoalStatus, units: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val pct = (s.fraction * 100).toInt()
    val value = Texts.goalValue(ctx, s.goal.type, s.value, units)
    val target = Texts.goalValue(ctx, s.goal.type, s.goal.target, units)
    val behind = !s.achieved && s.timeFraction > 0 && s.fraction < s.timeFraction - 0.15
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(goalTitle(ctx, s), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(Texts.goalPeriodLabel(s.goal.period)) + " · " + goalTimeLine(ctx, s),
                    style = MaterialTheme.typography.bodySmall, color = mt.ink2,
                )
            }
            if (s.achieved) Icon(Icons.Filled.CheckCircle, stringResource(R.string.goal_achieved), tint = mt.accent)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = mt.ink2)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Num.s, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.goal_of_target, target, pct), style = MaterialTheme.typography.bodySmall, color = if (behind) mt.danger else mt.ink2)
        }
        Spacer(Modifier.height(6.dp))
        FlatProgress(s.fraction.toFloat(), color = if (s.achieved) mt.accent else if (behind) mt.danger else mt.accent, description = stringResource(R.string.goal_of_target, target, pct))
    }
    Hairline()
}

@Composable
fun GoalsScreen(onCreate: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit, onProfile: () -> Unit) {
    val vm = appViewModel { GoalsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    BoundedLazyColumn(Modifier.statusBarsPadding()) {
        item { AppHeader(onSettings, onProfile) }
        item { ScreenTitle(stringResource(R.string.goals_title), stringResource(R.string.goals_subtitle)) }
        item { Spacer(Modifier.height(12.dp)); PrimaryButton(stringResource(R.string.goals_new), onCreate) }
        when {
            ui.loading -> item { LoadingState() }
            ui.error -> item { ErrorState(stringResource(R.string.goals_error)) }
            ui.active.isEmpty() && ui.archived.isEmpty() -> item {
                EmptyState(stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_body))
            }
            else -> {
                if (ui.active.isNotEmpty()) {
                    item {
                        val done = ui.active.count { it.achieved }
                        SectionHeader(stringResource(R.string.goals_active))
                        Caption(pluralStringResource(R.plurals.goals_done_count, done, done, ui.active.size))
                    }
                    items(ui.active, key = { it.goal.id }) { s -> GoalRow(s, ui.units) { onOpen(s.goal.id) } }
                }
                if (ui.archived.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.goals_archived)) }
                    items(ui.archived, key = { "a" + it.id }) { g ->
                        val spec = g.toSpec()
                        ListRow(
                            title = g.title.ifBlank { stringResource(Texts.goalTypeLabel(spec.type)) },
                            subtitle = stringResource(Texts.goalPeriodLabel(spec.period)) + " · " + Texts.goalValue(ctx, spec.type, spec.target, ui.units),
                            onClick = { onOpen(g.id) },
                        )
                    }
                }
            }
        }
    }
}
