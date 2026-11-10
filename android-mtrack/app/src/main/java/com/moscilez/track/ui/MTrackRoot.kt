package com.moscilez.track.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.container
import com.moscilez.track.ui.components.Hairline
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.details.ActivityDetailScreen
import com.moscilez.track.ui.goals.GoalDetailScreen
import com.moscilez.track.ui.goals.GoalEditScreen
import com.moscilez.track.ui.goals.GoalsScreen
import com.moscilez.track.ui.history.HistoryScreen
import com.moscilez.track.ui.home.HomeScreen
import com.moscilez.track.ui.onboarding.OnboardingScreen
import com.moscilez.track.ui.record.RecordScreen
import com.moscilez.track.ui.settings.DiagnosticsScreen
import com.moscilez.track.ui.settings.ProfileScreen
import com.moscilez.track.ui.settings.SettingsScreen
import com.moscilez.track.ui.start.StartScreen
import com.moscilez.track.ui.stats.StatsScreen
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.transfer.QrReceiveScreen
import com.moscilez.track.ui.transfer.QrSendScreen
import com.moscilez.track.ui.transfer.TransferScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val START = "start"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val GOALS = "goals"
    const val RECORD = "record"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val TRANSFER = "transfer?ids={ids}"
    const val QR_SEND = "qr_send"
    const val QR_RECEIVE = "qr_receive"
    const val DIAGNOSTICS = "diagnostics"
    const val ACTIVITY = "activity/{id}"
    const val GOAL = "goal/{id}"
    const val GOAL_EDIT = "goal_edit/{id}"

    fun activity(id: Long) = "activity/$id"
    fun goal(id: Long) = "goal/$id"
    fun goalEdit(id: Long) = "goal_edit/$id"
    fun transfer(ids: List<Long> = emptyList()) = if (ids.isEmpty()) "transfer" else "transfer?ids=${ids.joinToString(",")}"
}

enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    HOME(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
    HISTORY(Routes.HISTORY, R.string.tab_history, Icons.AutoMirrored.Outlined.Article),
    STATS(Routes.STATS, R.string.tab_stats, Icons.Filled.BarChart),
    GOALS(Routes.GOALS, R.string.tab_goals, Icons.Filled.TrackChanges),
}

private fun tabFor(route: String?): Tab? = when (route) {
    Routes.HOME, Routes.START -> Tab.HOME
    Routes.HISTORY -> Tab.HISTORY
    Routes.STATS -> Tab.STATS
    Routes.GOALS -> Tab.GOALS
    else -> null
}

/** ViewModel factory backed by the manual DI container. */
@Composable
inline fun <reified T : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> T): T {
    val c = LocalContext.current.container
    return viewModel(key = key) { create(c) }
}

class RootViewModel(private val c: AppContainer) : ViewModel() {
    private val _onboarding = MutableStateFlow<Boolean?>(null)
    val needsOnboarding: StateFlow<Boolean?> = _onboarding
    val tracking = c.trackingState.state

    init {
        viewModelScope.launch { _onboarding.value = !c.profileRepository.getSettings().onboardingDone }
    }

    fun onboardingFinished() { _onboarding.value = false }
}

@Composable
fun MTrackRoot() {
    val vm = appViewModel { RootViewModel(it) }
    val needsOnboarding by vm.needsOnboarding.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = tabFor(route)
    val mt = LocalMT.current

    LaunchedEffect(needsOnboarding) {
        when (needsOnboarding) {
            true -> nav.navigate(Routes.ONBOARDING) { launchSingleTop = true }
            false -> if (vm.tracking.value.isActive && nav.currentDestination?.route == Routes.HOME) {
                nav.navigate(Routes.RECORD) { launchSingleTop = true }
            }
            null -> Unit
        }
    }

    fun selectTab(t: Tab) {
        nav.navigate(t.route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(mt.bg)) {
        val wide = maxWidth >= 600.dp
        Scaffold(
            containerColor = mt.bg,
            contentWindowInsets = WindowInsets(0),
            bottomBar = { if (tab != null && !wide) BottomTabs(tab, ::selectTab) },
        ) { inner ->
            Row(Modifier.fillMaxSize().padding(inner)) {
                if (tab != null && wide) SideTabs(tab, ::selectTab)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (needsOnboarding != null) AppNavHost(nav, onOnboardingDone = vm::onboardingFinished)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, onOnboardingDone: () -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStart = { nav.navigate(Routes.START) },
                onOpenRecord = { nav.navigate(Routes.RECORD) { launchSingleTop = true } },
                onOpenActivity = { nav.navigate(Routes.activity(it)) },
                onSeeAll = {
                    nav.navigate(Routes.HISTORY) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                },
                onStats = {
                    nav.navigate(Routes.STATS) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.START) {
            StartScreen(
                onBack = back,
                onStarted = { nav.navigate(Routes.RECORD) { popUpTo(Routes.HOME); launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onClose = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) },
                onFinished = { id ->
                    nav.navigate(Routes.activity(id)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpen = { nav.navigate(Routes.activity(it)) },
                onExport = { nav.navigate(Routes.transfer(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(
                onOpenActivity = { nav.navigate(Routes.activity(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.GOALS) {
            GoalsScreen(
                onCreate = { nav.navigate(Routes.goalEdit(0)) },
                onOpen = { nav.navigate(Routes.goal(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.ACTIVITY, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            ActivityDetailScreen(id = it.arguments?.getLong("id") ?: 0, onBack = back)
        }
        composable(Routes.GOAL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments?.getLong("id") ?: 0
            GoalDetailScreen(id = id, onBack = back, onEdit = { nav.navigate(Routes.goalEdit(id)) })
        }
        composable(Routes.GOAL_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            GoalEditScreen(id = it.arguments?.getLong("id") ?: 0, onDone = back)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                onProfile = { nav.navigate(Routes.PROFILE) },
                onTransfer = { nav.navigate(Routes.transfer()) },
                onQrSend = { nav.navigate(Routes.QR_SEND) },
                onQrReceive = { nav.navigate(Routes.QR_RECEIVE) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onDataDeleted = {
                    nav.navigate(Routes.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
                    nav.navigate(Routes.ONBOARDING)
                },
            )
        }
        composable(Routes.PROFILE) { ProfileScreen(onBack = back) }
        composable(Routes.TRANSFER, arguments = listOf(navArgument("ids") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            val ids = it.arguments?.getString("ids")?.split(',')?.mapNotNull { s -> s.toLongOrNull() }.orEmpty()
            TransferScreen(sessionIds = ids, onBack = back)
        }
        composable(Routes.QR_SEND) { QrSendScreen(onBack = back) }
        composable(Routes.QR_RECEIVE) { QrReceiveScreen(onBack = back) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = back) }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                onOnboardingDone()
                nav.popBackStack(Routes.ONBOARDING, inclusive = true)
            })
        }
    }
}

@Composable
private fun TabItem(t: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val mt = LocalMT.current
    Column(
        modifier
            .then(if (selected) Modifier.background(mt.accentSoft, Square).border(1.dp, mt.accent, Square) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(t.icon, null, tint = if (selected) mt.accent else mt.ink2, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(t.label), style = MaterialTheme.typography.labelMedium, color = if (selected) mt.accent else mt.ink2, maxLines = 1)
    }
}

@Composable
private fun BottomTabs(current: Tab, onSelect: (Tab) -> Unit) {
    val mt = LocalMT.current
    Column(Modifier.fillMaxWidth().background(mt.bg)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp).padding(horizontal = 8.dp, vertical = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t -> TabItem(t, t == current, { onSelect(t) }, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun SideTabs(current: Tab, onSelect: (Tab) -> Unit) {
    val mt = LocalMT.current
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(112.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(8.dp).padding(top = 24.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab.entries.forEach { t -> TabItem(t, t == current, { onSelect(t) }, Modifier.fillMaxWidth().height(72.dp)) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(mt.line))
    }
}

@Suppress("unused")
private val Transparent = Color.Transparent
