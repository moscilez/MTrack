package com.moscilez.track.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.UserProfileEntity
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.findActivity
import com.moscilez.track.ui.goals.GoalUnits
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving
    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error

    fun finish(name: String, weight: Double?, units: String, done: () -> Unit) {
        _saving.value = true
        _error.value = false
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = c.profileRepository.getProfile()
                val profile = (existing ?: UserProfileEntity(
                    name = "", timezone = ZoneId.systemDefault().id, preferredType = ActivityType.RUN.name, createdAt = now, updatedAt = now,
                )).copy(name = name.trim(), weightKg = weight ?: existing?.weightKg, updatedAt = now)
                c.profileRepository.saveProfile(profile)
                c.profileRepository.updateSettings { it.copy(units = units, onboardingDone = true) }
                done()
            } catch (e: Exception) {
                _error.value = true
            } finally {
                _saving.value = false
            }
        }
    }
}

private data class IntroPage(val icon: ImageVector, val title: Int, val body: Int)

private val pages = listOf(
    IntroPage(Icons.Outlined.Route, R.string.onb_1_title, R.string.onb_1_body),
    IntroPage(Icons.Outlined.Insights, R.string.onb_2_title, R.string.onb_2_body),
    IntroPage(Icons.Outlined.Lock, R.string.onb_3_title, R.string.onb_3_body),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm = appViewModel { OnboardingViewModel(it) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { pages.size + 1 }
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else ctx.findActivity()?.finish()
    }

    var name by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var units by rememberSaveable { mutableStateOf("METRIC") }
    val w = GoalUnits.parse(weight)
    val weightErr = weight.isNotBlank() && (w == null || w !in 20.0..300.0)

    Column(Modifier.fillMaxSize().background(mt.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages.size) {
                TextButton({ scope.launch { pager.animateScrollToPage(pages.size) } }, shape = Square) {
                    Text(stringResource(R.string.onb_skip), color = mt.ink2)
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(32.dp))
                    if (page < pages.size) {
                        val p = pages[page]
                        Text("0${page + 1}", style = Num.l, color = mt.accent)
                        Box(Modifier.size(72.dp).border(1.dp, mt.ink, Square), contentAlignment = Alignment.Center) {
                            Icon(p.icon, null, Modifier.size(32.dp), tint = mt.ink)
                        }
                        Text(stringResource(p.title), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, color = mt.ink2)
                    } else {
                        Text("04", style = Num.l, color = mt.accent)
                        Text(stringResource(R.string.onb_profile_title), style = MaterialTheme.typography.displaySmall)
                        Caption(stringResource(R.string.onb_profile_body))
                        MTTextField(name, { name = it.take(40) }, stringResource(R.string.profile_name))
                        MTTextField(
                            weight, { weight = it.take(5) }, stringResource(R.string.profile_weight),
                            keyboardType = KeyboardType.Decimal, isError = weightErr,
                            supporting = if (weightErr) stringResource(R.string.profile_err_weight) else stringResource(R.string.profile_weight_sub),
                        )
                        Segmented(
                            listOf("METRIC", "IMPERIAL"), units,
                            { stringResource(if (it == "METRIC") R.string.start_units_km else R.string.start_units_mi) },
                            { units = it },
                        )
                        if (error) Text(stringResource(R.string.onb_error), color = mt.danger, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Column(Modifier.widthIn(max = ContentMaxWidth).align(Alignment.CenterHorizontally).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val pos = stringResource(R.string.cd_onb_page, pager.currentPage + 1, pages.size + 1)
            Row(Modifier.semantics { contentDescription = pos }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size + 1) { i ->
                    Box(Modifier.width(if (i == pager.currentPage) 24.dp else 8.dp).height(4.dp).background(if (i == pager.currentPage) mt.accent else mt.line))
                }
            }
            if (pager.currentPage < pages.size) {
                PrimaryButton(stringResource(R.string.action_continue), { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
            } else {
                PrimaryButton(
                    stringResource(R.string.onb_start), { vm.finish(name, w, units, onDone) },
                    enabled = !weightErr && !saving, loading = saving,
                )
            }
        }
    }
}
