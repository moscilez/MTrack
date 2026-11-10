package com.moscilez.track.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.repo.HistoryFilter
import com.moscilez.track.data.repo.HistorySort
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.stats.Periods
import com.moscilez.track.domain.stats.StatsPeriod
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.AppHeader
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.ErrorState
import com.moscilez.track.ui.components.Hairline
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.ScreenTitle
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.SquareChip
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.home.sessionLine
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class HistoryPeriod { ALL, WEEK, MONTH, YEAR }

data class HistoryUi(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val items: List<ActivitySessionEntity> = emptyList(),
    val endReached: Boolean = false,
    val search: String = "",
    val types: Set<ActivityType> = emptySet(),
    val period: HistoryPeriod = HistoryPeriod.ALL,
    val sort: HistorySort = HistorySort.DATE_DESC,
    val selected: Set<Long> = emptySet(),
    val units: String = "METRIC",
)

@OptIn(FlowPreview::class)
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HistoryUi())
    val ui: StateFlow<HistoryUi> = _ui
    private val searchInput = MutableStateFlow("")
    private var job: Job? = null

    init {
        viewModelScope.launch { c.profileRepository.settings.collect { s -> _ui.update { it.copy(units = s.units) } } }
        viewModelScope.launch {
            searchInput.debounce(300).distinctUntilChanged().drop(1).collect { reload() }
        }
        // Any change to the stored activities (finish, import, delete) refreshes the list.
        viewModelScope.launch {
            c.activityRepository.finished.map { l -> l.size to l.maxOfOrNull { it.updatedAt } }
                .distinctUntilChanged().collect { reload() }
        }
    }

    private fun filter(u: HistoryUi): HistoryFilter {
        val range = when (u.period) {
            HistoryPeriod.ALL -> null
            HistoryPeriod.WEEK -> Periods.range(StatsPeriod.WEEK, System.currentTimeMillis(), ZoneId.systemDefault())
            HistoryPeriod.MONTH -> Periods.range(StatsPeriod.MONTH, System.currentTimeMillis(), ZoneId.systemDefault())
            HistoryPeriod.YEAR -> Periods.range(StatsPeriod.YEAR, System.currentTimeMillis(), ZoneId.systemDefault())
        }
        return HistoryFilter(u.types, range?.start, range?.end, u.search, u.sort)
    }

    fun reload() {
        job?.cancel()
        _ui.update { it.copy(loading = it.items.isEmpty(), error = false) }
        job = viewModelScope.launch {
            try {
                val page = c.activityRepository.history(filter(_ui.value), 0, PAGE)
                _ui.update { it.copy(loading = false, items = page, endReached = page.size < PAGE, selected = it.selected.intersect(page.map { p -> p.id }.toSet())) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun loadMore() {
        val u = _ui.value
        if (u.loading || u.loadingMore || u.endReached || u.error) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = c.activityRepository.history(filter(u), u.items.size, PAGE)
                _ui.update { it.copy(loadingMore = false, items = (it.items + page).distinctBy { s -> s.id }, endReached = page.size < PAGE) }
            } catch (e: Exception) {
                _ui.update { it.copy(loadingMore = false, endReached = true) }
            }
        }
    }

    fun setSearch(q: String) { _ui.update { it.copy(search = q) }; searchInput.value = q }
    fun toggleType(t: ActivityType) { _ui.update { it.copy(types = if (t in it.types) it.types - t else it.types + t, items = emptyList()) }; reload() }
    fun clearTypes() { _ui.update { it.copy(types = emptySet(), items = emptyList()) }; reload() }
    fun setPeriod(p: HistoryPeriod) { _ui.update { it.copy(period = p, items = emptyList()) }; reload() }
    fun setSort(s: HistorySort) { _ui.update { it.copy(sort = s, items = emptyList()) }; reload() }
    fun toggleSelect(id: Long) = _ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = _ui.update { it.copy(selected = it.items.map { s -> s.id }.toSet()) }
    fun clearSelection() = _ui.update { it.copy(selected = emptySet()) }
    fun deleteSelected() {
        val ids = _ui.value.selected.toList()
        viewModelScope.launch {
            c.activityRepository.delete(ids)
            _ui.update { it.copy(selected = emptySet()) }
        }
    }

    companion object { const val PAGE = 30 }
}

private fun sortLabel(s: HistorySort): Int = when (s) {
    HistorySort.DATE_DESC -> R.string.sort_newest
    HistorySort.DATE_ASC -> R.string.sort_oldest
    HistorySort.DISTANCE_DESC -> R.string.sort_distance
    HistorySort.DURATION_DESC -> R.string.sort_duration
    HistorySort.SPEED_DESC -> R.string.sort_speed
}

private fun periodLabel(p: HistoryPeriod): Int = when (p) {
    HistoryPeriod.ALL -> R.string.period_all
    HistoryPeriod.WEEK -> R.string.period_week
    HistoryPeriod.MONTH -> R.string.period_month
    HistoryPeriod.YEAR -> R.string.period_year
}

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onExport: (List<Long>) -> Unit, onSettings: () -> Unit, onProfile: () -> Unit) {
    val vm = appViewModel { HistoryViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val selecting = ui.selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler(enabled = selecting) { vm.clearSelection() }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd) vm.loadMore() }

    BoundedLazyColumn(Modifier.statusBarsPadding(), state = listState) {
        item {
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_selection)) }
                    Text(
                        pluralStringResource(R.plurals.history_selected, ui.selected.size, ui.selected.size),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::selectAll, shape = Square) { Text(stringResource(R.string.action_select_all), color = mt.accent) }
                    IconButton(onClick = { onExport(ui.selected.toList()) }) { Icon(Icons.Filled.IosShare, stringResource(R.string.action_export)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), tint = mt.danger) }
                }
            } else AppHeader(onSettings, onProfile)
        }
        item { ScreenTitle(stringResource(R.string.history_title)) }
        item {
            OutlinedTextField(
                value = ui.search,
                onValueChange = vm::setSearch,
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (ui.search.isNotEmpty()) {
                    { IconButton(onClick = { vm.setSearch("") }) { Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_search)) } }
                } else null,
                singleLine = true,
                shape = Square,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = mt.accent, unfocusedBorderColor = mt.line, cursorColor = mt.accent,
                    focusedContainerColor = mt.surface, unfocusedContainerColor = mt.surface,
                ),
            )
        }
        item {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SquareChip(stringResource(R.string.action_all), ui.types.isEmpty(), vm::clearTypes) }
                items(ActivityType.entries) { t ->
                    SquareChip(stringResource(t.label()), t in ui.types, { vm.toggleType(t) }, leading = t.icon())
                }
            }
        }
        item {
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Segmented(HistoryPeriod.entries, ui.period, { stringResource(periodLabel(it)) }, vm::setPeriod, Modifier.weight(1f))
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.cd_sort, stringResource(sortLabel(ui.sort))))
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        HistorySort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sortLabel(s)), color = if (s == ui.sort) mt.accent else mt.ink) },
                                onClick = { vm.setSort(s); sortMenu = false },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Hairline()
        }
        when {
            ui.loading -> item { LoadingState() }
            ui.error -> item { ErrorState(stringResource(R.string.history_error), onRetry = vm::reload) }
            ui.items.isEmpty() -> item {
                val filtered = ui.search.isNotBlank() || ui.types.isNotEmpty() || ui.period != HistoryPeriod.ALL
                EmptyState(
                    stringResource(if (filtered) R.string.history_nothing_found else R.string.home_empty_title),
                    stringResource(if (filtered) R.string.history_nothing_found_body else R.string.home_empty_body),
                )
            }
            else -> {
                items(ui.items, key = { it.id }) { s ->
                    val sel = s.id in ui.selected
                    ListRow(
                        title = s.title.ifBlank { stringResource(ActivityType.from(s.type).label()) },
                        subtitle = Formatters.date(ctx, s.startTime) + " · " + sessionLine(ctx, s, ui.units),
                        icon = ActivityType.from(s.type).icon(),
                        selected = sel,
                        chevron = !selecting,
                        trailing = if (selecting) {
                            { Icon(if (sel) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null, tint = if (sel) mt.accent else mt.ink2) }
                        } else null,
                        onLongClick = { vm.toggleSelect(s.id) },
                        onClick = { if (selecting) vm.toggleSelect(s.id) else onOpen(s.id) },
                    )
                }
                if (ui.loadingMore) item { LoadingState() }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.history_delete_title, ui.selected.size, ui.selected.size),
            body = stringResource(R.string.history_delete_body),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { vm.deleteSelected(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}
