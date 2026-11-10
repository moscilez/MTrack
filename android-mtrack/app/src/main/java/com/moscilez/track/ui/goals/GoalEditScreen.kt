package com.moscilez.track.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.FullscreenLoading
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.SquareChip
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.format.Texts
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Converts between stored base units (m, ms, count) and the numbers the user types. */
object GoalUnits {
    fun toDisplay(type: GoalType, base: Double, units: String): Double = when (type) {
        GoalType.PERIOD_DISTANCE, GoalType.TYPE_DISTANCE -> base / (if (Formatters.imperial(units)) Formatters.M_PER_MILE else 1000.0)
        GoalType.ACTIVE_TIME -> base / 3_600_000.0
        else -> base
    }

    fun toBase(type: GoalType, display: Double, units: String): Double = when (type) {
        GoalType.PERIOD_DISTANCE, GoalType.TYPE_DISTANCE -> display * (if (Formatters.imperial(units)) Formatters.M_PER_MILE else 1000.0)
        GoalType.ACTIVE_TIME -> display * 3_600_000.0
        else -> display
    }

    fun unitLabel(type: GoalType, units: String): Int? = when (type) {
        GoalType.PERIOD_DISTANCE, GoalType.TYPE_DISTANCE -> if (Formatters.imperial(units)) R.string.unit_mi else R.string.unit_km
        GoalType.ACTIVE_TIME -> R.string.unit_hours
        else -> null
    }

    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    fun print(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

data class GoalEditUi(val loading: Boolean = true, val existing: GoalEntity? = null, val units: String = "METRIC", val saved: Boolean = false)

class GoalEditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val _ui = MutableStateFlow(GoalEditUi())
    val ui: StateFlow<GoalEditUi> = _ui

    init {
        viewModelScope.launch {
            val units = c.profileRepository.getSettings().units
            _ui.value = GoalEditUi(loading = false, existing = if (id > 0) c.goalRepository.get(id) else null, units = units)
        }
    }

    fun save(type: GoalType, title: String, targetBase: Double, activityType: ActivityType?, period: GoalPeriod, deadline: Long?, manual: Double) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val e = _ui.value.existing
            if (e == null) {
                c.goalRepository.create(
                    GoalEntity(
                        uuid = "", type = type.name, title = title.trim(), target = targetBase,
                        activityType = activityType?.name, period = period.name, startDate = now,
                        deadline = deadline, manualProgress = manual, createdAt = now,
                    )
                )
            } else {
                c.goalRepository.update(
                    e.copy(
                        type = type.name, title = title.trim(), target = targetBase, activityType = activityType?.name,
                        period = period.name, deadline = deadline, manualProgress = manual,
                    )
                )
            }
            _ui.value = _ui.value.copy(saved = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalEditScreen(id: Long, onDone: () -> Unit) {
    val vm = appViewModel("goal-edit-$id") { GoalEditViewModel(it, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onDone() }

    Scaffold(
        topBar = { DetailTopBar(stringResource(if (id > 0) R.string.goal_edit_title else R.string.goals_new), onDone) },
        containerColor = LocalMT.current.bg,
    ) { inner ->
        if (ui.loading) { Box(Modifier.padding(inner)) { FullscreenLoading() }; return@Scaffold }
        val ctx = LocalContext.current
        val e = ui.existing
        val units = ui.units
        var type by rememberSaveable { mutableStateOf(e?.type?.let { t -> GoalType.entries.firstOrNull { it.name == t } } ?: GoalType.PERIOD_DISTANCE) }
        var title by rememberSaveable { mutableStateOf(e?.title.orEmpty()) }
        var target by rememberSaveable { mutableStateOf(e?.let { GoalUnits.print(GoalUnits.toDisplay(type, it.target, units)) } ?: "") }
        var activity by rememberSaveable { mutableStateOf(e?.activityType?.let { ActivityType.from(it) } ?: ActivityType.RUN) }
        var period by rememberSaveable { mutableStateOf(e?.period?.let { p -> GoalPeriod.entries.firstOrNull { it.name == p } } ?: GoalPeriod.WEEK) }
        var deadline by rememberSaveable { mutableStateOf(e?.deadline) }
        var manual by rememberSaveable { mutableStateOf(e?.manualProgress?.let { GoalUnits.print(it) } ?: "0") }
        var showPicker by remember { mutableStateOf(false) }
        var tried by remember { mutableStateOf(false) }

        val targetValue = GoalUnits.parse(target)
        val manualValue = GoalUnits.parse(manual)
        val targetError = targetValue == null || targetValue <= 0 || targetValue > 1_000_000
        val deadlineError = period == GoalPeriod.DEADLINE && (deadline == null || deadline!! < System.currentTimeMillis() - 86_400_000)
        val manualError = type == GoalType.CUSTOM && (manualValue == null || manualValue < 0)
        val valid = !targetError && !deadlineError && !manualError && (type != GoalType.CUSTOM || title.isNotBlank())

        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding).navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                SectionHeader(stringResource(R.string.goal_field_type))
                GoalType.entries.forEach { t ->
                    ListRow(
                        title = stringResource(Texts.goalTypeLabel(t)),
                        selected = t == type,
                        chevron = false,
                        onClick = {
                            // Keep the typed number meaningful when switching between unit kinds.
                            if (t != type) target = ""
                            type = t
                        },
                    )
                }
                if (type == GoalType.TYPE_DISTANCE) {
                    SectionHeader(stringResource(R.string.goal_field_activity))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityType.entries.forEach { a -> SquareChip(stringResource(a.label()), a == activity, { activity = a }, leading = a.icon()) }
                    }
                }
                SectionHeader(stringResource(R.string.goal_field_details))
                MTTextField(
                    title, { title = it.take(80) },
                    stringResource(if (type == GoalType.CUSTOM) R.string.goal_field_title_required else R.string.goal_field_title),
                    isError = tried && type == GoalType.CUSTOM && title.isBlank(),
                )
                Spacer(Modifier.height(12.dp))
                val unitRes = GoalUnits.unitLabel(type, units)
                MTTextField(
                    target, { target = it.take(10) },
                    stringResource(R.string.goal_field_target) + (unitRes?.let { ", " + stringResource(it) } ?: ""),
                    keyboardType = KeyboardType.Decimal,
                    isError = tried && targetError,
                    supporting = if (tried && targetError) stringResource(R.string.goal_err_target) else null,
                )
                if (type == GoalType.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    MTTextField(
                        manual, { manual = it.take(10) }, stringResource(R.string.goal_field_progress),
                        keyboardType = KeyboardType.Decimal, isError = tried && manualError,
                    )
                }
                SectionHeader(stringResource(R.string.goal_field_period))
                Segmented(GoalPeriod.entries, period, { stringResource(Texts.goalPeriodLabel(it)) }, { period = it })
                if (period == GoalPeriod.DEADLINE) {
                    Spacer(Modifier.height(8.dp))
                    ListRow(
                        title = deadline?.let { Formatters.date(ctx, it) } ?: stringResource(R.string.goal_pick_deadline),
                        subtitle = if (tried && deadlineError) stringResource(R.string.goal_err_deadline) else stringResource(R.string.goal_field_deadline),
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = { showPicker = true },
                    )
                }
                if (tried && !valid) Caption(stringResource(R.string.goal_err_form), Modifier.padding(top = 12.dp), color = LocalMT.current.danger)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(stringResource(R.string.action_save), {
                    tried = true
                    if (valid) vm.save(
                        type, title, GoalUnits.toBase(type, targetValue!!, units),
                        if (type == GoalType.TYPE_DISTANCE) activity else null, period,
                        if (period == GoalPeriod.DEADLINE) deadline else null, manualValue ?: 0.0,
                    )
                })
            }
        }

        if (showPicker) {
            val today = System.currentTimeMillis()
            val state = rememberDatePickerState(
                initialSelectedDateMillis = deadline ?: (today + 30L * 86_400_000),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today - 86_400_000
                },
            )
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                shape = Square,
                confirmButton = {
                    TextButton(onClick = {
                        // End of the chosen day, so the deadline day still counts.
                        state.selectedDateMillis?.let { deadline = it + 86_399_000 }
                        showPicker = false
                    }) { Text(stringResource(R.string.action_ok)) }
                },
                dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
            ) { DatePicker(state) }
        }
    }
}
