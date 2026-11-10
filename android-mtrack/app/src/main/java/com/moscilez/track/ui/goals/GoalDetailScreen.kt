package com.moscilez.track.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.data.db.GoalProgressEntity
import com.moscilez.track.data.repo.toSpec
import com.moscilez.track.data.repo.toStat
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.domain.stats.GoalEvaluator
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.Hairline
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class GoalDetailUi(
    val loading: Boolean = true,
    val goal: GoalEntity? = null,
    val status: GoalStatus? = null,
    val history: List<GoalProgressEntity> = emptyList(),
    val units: String = "METRIC",
)

class GoalDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val deleted = MutableStateFlow(false)

    val ui: StateFlow<GoalDetailUi> = combine(
        c.goalRepository.all.map { l -> l.firstOrNull { it.id == id } },
        c.goalRepository.history(id),
        c.activityRepository.finished,
        c.profileRepository.settings,
    ) { goal, history, finished, settings ->
        val status = goal?.let { GoalEvaluator.evaluate(it.toSpec(), finished.map { s -> s.toStat() }, System.currentTimeMillis(), ZoneId.systemDefault()) }
        GoalDetailUi(false, goal, status, history.sortedByDescending { it.periodStart }, settings.units)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalDetailUi())

    fun setArchived(archived: Boolean) {
        val g = ui.value.goal ?: return
        viewModelScope.launch { c.goalRepository.update(g.copy(archived = archived)) }
    }

    fun delete() {
        val g = ui.value.goal ?: return
        viewModelScope.launch { c.goalRepository.delete(g); deleted.value = true }
    }
}

@Composable
fun GoalDetailScreen(id: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val vm = appViewModel("goal-$id") { GoalDetailViewModel(it, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            DetailTopBar(stringResource(R.string.goal_detail_title), onBack) {
                if (ui.goal != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
            }
        },
        containerColor = mt.bg,
    ) { inner ->
        val g = ui.goal
        val s = ui.status
        BoundedLazyColumn(Modifier.padding(inner)) {
            if (ui.loading) { item { LoadingState() }; return@BoundedLazyColumn }
            if (g == null || s == null) {
                item { EmptyState(stringResource(R.string.goal_missing_title), stringResource(R.string.goal_missing_body), action = stringResource(R.string.action_back), onAction = onBack) }
                return@BoundedLazyColumn
            }
            val type = s.goal.type
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(goalTitle(ctx, s), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(Texts.goalTypeLabel(type)) + " · " + stringResource(Texts.goalPeriodLabel(s.goal.period)) +
                            (s.goal.activityType?.let { " · " + stringResource(it.label()) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = mt.ink2,
                    )
                    if (g.archived) Caption(stringResource(R.string.goal_is_archived), Modifier.padding(top = 4.dp))
                }
            }
            item {
                val pct = (s.fraction * 100).toInt()
                Column(Modifier.padding(top = 24.dp).semantics(mergeDescendants = true) {}) {
                    Caption(stringResource(R.string.goal_progress))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Texts.goalValue(ctx, type, s.value, ui.units), style = Num.l, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Text("$pct%", style = Num.m, color = if (s.achieved) mt.accent else mt.ink2, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    FlatProgress(s.fraction.toFloat())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.goal_of_target, Texts.goalValue(ctx, type, s.goal.target, ui.units), pct), style = MaterialTheme.typography.bodyMedium, color = mt.ink2)
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    KeyValueRow(stringResource(R.string.goal_period_range), Formatters.date(ctx, s.periodStart) + " — " + if (s.periodEnd == Long.MAX_VALUE) "…" else Formatters.date(ctx, s.periodEnd - 1))
                    KeyValueRow(stringResource(R.string.goal_time_left), goalTimeLine(ctx, s))
                    if (!s.achieved && type != GoalType.CUSTOM) {
                        val remaining = (s.goal.target - s.value).coerceAtLeast(0.0)
                        KeyValueRow(stringResource(R.string.goal_remaining), Texts.goalValue(ctx, type, remaining, ui.units))
                    }
                    if (s.timeFraction > 0 && !s.achieved) {
                        val onTrack = s.fraction >= s.timeFraction - 0.05
                        KeyValueRow(
                            stringResource(R.string.goal_pace),
                            stringResource(if (onTrack) R.string.goal_on_track else R.string.goal_behind),
                            valueColor = if (onTrack) mt.accent else mt.danger,
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.goal_history)) }
            if (ui.history.isEmpty()) {
                item { Text(stringResource(R.string.goal_history_empty), style = MaterialTheme.typography.bodyMedium, color = mt.ink2) }
            } else {
                items(ui.history, key = { it.periodStart }) { h ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (h.achieved) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                stringResource(if (h.achieved) R.string.goal_achieved else R.string.goal_not_achieved),
                                tint = if (h.achieved) mt.accent else mt.ink2,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                Formatters.date(ctx, h.periodStart) + if (h.periodEnd != Long.MAX_VALUE) " — " + Formatters.date(ctx, h.periodEnd - 1) else "",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            Text(Texts.goalValue(ctx, type, h.value, ui.units), style = Num.s.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize))
                        }
                        Hairline()
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 28.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(if (g.archived) R.string.goal_unarchive else R.string.goal_archive), { vm.setArchived(!g.archived) }, Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.action_delete), { confirmDelete = true }, Modifier.weight(1f), danger = true)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.goal_delete_title),
            body = stringResource(R.string.goal_delete_body),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
