package com.moscilez.track.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.FullscreenLoading
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.SquareChip
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.goals.GoalUnits
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class ProfileUi(val loading: Boolean = true, val profile: UserProfileEntity? = null, val saved: Boolean = false)

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui

    init {
        viewModelScope.launch { _ui.value = ProfileUi(loading = false, profile = c.profileRepository.getProfile()) }
    }

    fun save(name: String, height: Double?, weight: Double?, birthYear: Int?, sex: String?, preferred: ActivityType) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val base = _ui.value.profile ?: UserProfileEntity(name = "", timezone = zone.id, preferredType = preferred.name, createdAt = now, updatedAt = now)
            c.profileRepository.saveProfile(
                base.copy(
                    name = name.trim(), heightCm = height, weightKg = weight,
                    birthDate = birthYear?.let { LocalDate.of(it, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() },
                    sex = sex, timezone = zone.id, preferredType = preferred.name,
                )
            )
            _ui.value = _ui.value.copy(saved = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.profile_title), onBack) }, containerColor = LocalMT.current.bg) { inner ->
        if (ui.loading) { Box(Modifier.padding(inner)) { FullscreenLoading() }; return@Scaffold }
        val p = ui.profile
        var name by rememberSaveable { mutableStateOf(p?.name.orEmpty()) }
        var height by rememberSaveable { mutableStateOf(p?.heightCm?.let { GoalUnits.print(it) } ?: "") }
        var weight by rememberSaveable { mutableStateOf(p?.weightKg?.let { GoalUnits.print(it) } ?: "") }
        var year by rememberSaveable {
            mutableStateOf(p?.birthDate?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year.toString() } ?: "")
        }
        var sex by rememberSaveable { mutableStateOf(p?.sex ?: "NONE") }
        var preferred by rememberSaveable { mutableStateOf(ActivityType.from(p?.preferredType ?: "RUN")) }

        val h = GoalUnits.parse(height)
        val w = GoalUnits.parse(weight)
        val y = year.trim().toIntOrNull()
        val thisYear = LocalDate.now().year
        val hErr = height.isNotBlank() && (h == null || h !in 50.0..250.0)
        val wErr = weight.isNotBlank() && (w == null || w !in 20.0..300.0)
        val yErr = year.isNotBlank() && (y == null || y !in 1900..thisYear)

        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding).navigationBarsPadding().padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MTTextField(name, { name = it.take(40) }, stringResource(R.string.profile_name))
                MTTextField(height, { height = it.take(5) }, stringResource(R.string.profile_height), keyboardType = KeyboardType.Decimal, isError = hErr, supporting = if (hErr) stringResource(R.string.profile_err_height) else null)
                MTTextField(weight, { weight = it.take(5) }, stringResource(R.string.profile_weight), keyboardType = KeyboardType.Decimal, isError = wErr, supporting = if (wErr) stringResource(R.string.profile_err_weight) else stringResource(R.string.profile_weight_sub))
                MTTextField(year, { year = it.filter(Char::isDigit).take(4) }, stringResource(R.string.profile_birth_year), keyboardType = KeyboardType.Number, isError = yErr)
                Caption(stringResource(R.string.profile_sex))
                Segmented(
                    listOf("NONE", "FEMALE", "MALE"), sex,
                    { stringResource(when (it) { "FEMALE" -> R.string.sex_female; "MALE" -> R.string.sex_male; else -> R.string.sex_none }) },
                    { sex = it },
                )
                SectionHeader(stringResource(R.string.profile_preferred))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityType.entries.forEach { a -> SquareChip(stringResource(a.label()), a == preferred, { preferred = a }, leading = a.icon()) }
                }
                Caption(stringResource(R.string.profile_local_note))
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    stringResource(R.string.action_save),
                    { vm.save(name, h, w, y, sex.takeIf { it != "NONE" }, preferred) },
                    enabled = !hErr && !wErr && !yErr,
                )
            }
        }
    }
}
