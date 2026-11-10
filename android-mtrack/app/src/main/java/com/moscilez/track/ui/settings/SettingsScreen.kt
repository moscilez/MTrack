package com.moscilez.track.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.BuildConfig
import com.moscilez.track.R
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.LoadingState
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.SwitchRow
import com.moscilez.track.ui.components.findActivity
import com.moscilez.track.ui.goals.GoalUnits
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<UserSettingsEntity?> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recording: StateFlow<Boolean> = c.trackingState.state.map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val deleting = MutableStateFlow(false)
    val deleted = MutableStateFlow(false)

    fun update(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        viewModelScope.launch { c.profileRepository.updateSettings(transform) }
    }

    /** Saves first so the recreated activity reads the new locale. */
    fun setLanguage(lang: String, after: () -> Unit) {
        viewModelScope.launch {
            c.profileRepository.updateSettings { it.copy(language = lang) }
            after()
        }
    }

    fun deleteAll() {
        if (c.trackingState.state.value.isActive) return
        deleting.value = true
        viewModelScope.launch {
            runCatching { c.transferRepository.deleteAllData() }
            deleting.value = false
            deleted.value = true
        }
    }
}

private val LANGS = listOf("system", "ru", "en")
private val THEMES = listOf("SYSTEM", "LIGHT", "DARK")
private val DATE_FORMATS = listOf("SYSTEM", "DMY", "YMD", "MDY")
private val INTERVALS = listOf(1000L, 2000L, 5000L)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onTransfer: () -> Unit,
    onQrSend: () -> Unit,
    onQrReceive: () -> Unit,
    onDiagnostics: () -> Unit,
    onDataDeleted: () -> Unit,
) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    var confirmDelete by remember { mutableStateOf(false) }
    var codeWord by remember { mutableStateOf("") }

    LaunchedEffect(deleted) {
        if (deleted) {
            onDataDeleted()
            ctx.findActivity()?.recreate()
        }
    }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.settings_title), onBack) }, containerColor = mt.bg) { inner ->
        val st = s
        BoundedLazyColumn(Modifier.padding(inner)) {
            if (st == null) { item { LoadingState() }; return@BoundedLazyColumn }
            item {
                ListRow(stringResource(R.string.profile_title), subtitle = stringResource(R.string.settings_profile_sub), icon = Icons.Outlined.AccountCircle, onClick = onProfile)
            }

            item { SectionHeader(stringResource(R.string.settings_general)) }
            item {
                Caption(stringResource(R.string.settings_units))
                Segmented(listOf("METRIC", "IMPERIAL"), st.units, { stringResource(if (it == "METRIC") R.string.start_units_km else R.string.start_units_mi) }, { u -> vm.update { it.copy(units = u) } }, Modifier.padding(top = 6.dp, bottom = 16.dp))
                Caption(stringResource(R.string.settings_language))
                Segmented(
                    LANGS, st.language,
                    { stringResource(when (it) { "ru" -> R.string.lang_ru; "en" -> R.string.lang_en; else -> R.string.lang_system }) },
                    { l -> if (l != st.language) vm.setLanguage(l) { ctx.findActivity()?.recreate() } },
                    Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Caption(stringResource(R.string.settings_theme))
                Segmented(
                    THEMES, st.theme,
                    { stringResource(when (it) { "LIGHT" -> R.string.theme_light; "DARK" -> R.string.theme_dark; else -> R.string.theme_system }) },
                    { t -> vm.update { it.copy(theme = t) } },
                    Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Caption(stringResource(R.string.settings_date_format))
                Segmented(
                    DATE_FORMATS, st.dateFormat,
                    { when (it) { "DMY" -> "31.12"; "YMD" -> "12-31"; "MDY" -> "12/31"; else -> stringResource(R.string.lang_system) } },
                    { f -> vm.update { it.copy(dateFormat = f) } },
                    Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                SwitchRow(stringResource(R.string.settings_24h), st.use24h, { v -> vm.update { it.copy(use24h = v) } })
            }

            item { SectionHeader(stringResource(R.string.settings_recording)) }
            item {
                Caption(stringResource(R.string.settings_gps_accuracy))
                Segmented(
                    listOf("HIGH", "BALANCED"), st.gpsAccuracy,
                    { stringResource(if (it == "HIGH") R.string.gps_mode_high else R.string.gps_mode_balanced) },
                    { a -> vm.update { it.copy(gpsAccuracy = a) } },
                    Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Caption(stringResource(R.string.settings_gps_interval))
                Segmented(
                    INTERVALS, INTERVALS.minByOrNull { kotlin.math.abs(it - st.gpsIntervalMs) } ?: 2000L,
                    { stringResource(R.string.seconds_short, (it / 1000).toInt()) },
                    { i -> vm.update { it.copy(gpsIntervalMs = i) } },
                    Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                Caption(stringResource(R.string.settings_gps_hint), Modifier.padding(bottom = 8.dp))
                SwitchRow(stringResource(R.string.settings_auto_pause), st.autoPause, { v -> vm.update { it.copy(autoPause = v) } }, stringResource(R.string.settings_auto_pause_sub))
                SwitchRow(stringResource(R.string.settings_auto_stops), st.autoStops, { v -> vm.update { it.copy(autoStops = v) } })
                SwitchRow(stringResource(R.string.settings_background), st.backgroundTracking, { v -> vm.update { it.copy(backgroundTracking = v) } }, stringResource(R.string.settings_background_sub))
                SwitchRow(stringResource(R.string.settings_notifications), st.notifications, { v -> vm.update { it.copy(notifications = v) } })
                SwitchRow(stringResource(R.string.settings_sounds), st.sounds, { v -> vm.update { it.copy(sounds = v) } })
                SwitchRow(stringResource(R.string.settings_vibration), st.vibration, { v -> vm.update { it.copy(vibration = v) } })
            }

            item { SectionHeader(stringResource(R.string.settings_display)) }
            item {
                SwitchRow(stringResource(R.string.settings_show_pace), st.showPace, { v -> vm.update { it.copy(showPace = v) } })
                SwitchRow(stringResource(R.string.settings_show_altitude), st.showAltitude, { v -> vm.update { it.copy(showAltitude = v) } })
                SwitchRow(stringResource(R.string.settings_show_calories), st.showCalories, { v -> vm.update { it.copy(showCalories = v) } })
                SwitchRow(stringResource(R.string.settings_map_grid), st.mapGrid, { v -> vm.update { it.copy(mapGrid = v) } })
            }

            item { SectionHeader(stringResource(R.string.settings_vehicle)) }
            item {
                var threshold by remember(st.carSpeedThresholdKmh) { mutableStateOf(GoalUnits.print(st.carSpeedThresholdKmh)) }
                var fuel by remember(st.carFuelLPer100) { mutableStateOf(st.carFuelLPer100?.let { GoalUnits.print(it) } ?: "") }
                val tVal = GoalUnits.parse(threshold)
                val fVal = GoalUnits.parse(fuel)
                MTTextField(
                    threshold,
                    { v -> threshold = v.take(5); GoalUnits.parse(v)?.takeIf { it in 10.0..300.0 }?.let { n -> vm.update { it.copy(carSpeedThresholdKmh = n) } } },
                    stringResource(R.string.settings_car_threshold),
                    keyboardType = KeyboardType.Decimal,
                    isError = tVal == null || tVal !in 10.0..300.0,
                    supporting = stringResource(R.string.settings_car_threshold_sub),
                )
                Spacer(Modifier.height(8.dp))
                MTTextField(
                    fuel,
                    { v ->
                        fuel = v.take(5)
                        val n = GoalUnits.parse(v)
                        if (v.isBlank()) vm.update { it.copy(carFuelLPer100 = null) }
                        else if (n != null && n in 1.0..50.0) vm.update { it.copy(carFuelLPer100 = n) }
                    },
                    stringResource(R.string.settings_car_fuel),
                    keyboardType = KeyboardType.Decimal,
                    isError = fuel.isNotBlank() && (fVal == null || fVal !in 1.0..50.0),
                    supporting = stringResource(R.string.settings_car_fuel_sub),
                )
                Caption(stringResource(R.string.car_not_certified), Modifier.padding(top = 8.dp))
            }

            item { SectionHeader(stringResource(R.string.settings_recommendations)) }
            item {
                SwitchRow(stringResource(R.string.settings_recs_enabled), st.recommendationsEnabled, { v -> vm.update { it.copy(recommendationsEnabled = v) } }, stringResource(R.string.settings_recs_sub))
                if (st.mutedRecommendations.isNotBlank()) {
                    ListRow(stringResource(R.string.settings_recs_unmute), chevron = false, onClick = { vm.update { it.copy(mutedRecommendations = "") } })
                }
            }

            item { SectionHeader(stringResource(R.string.settings_privacy)) }
            item {
                SwitchRow(stringResource(R.string.settings_trim_ends), st.privacyTrimRouteEnds, { v -> vm.update { it.copy(privacyTrimRouteEnds = v) } }, stringResource(R.string.settings_trim_ends_sub))
                Caption(stringResource(R.string.settings_privacy_note), Modifier.padding(top = 8.dp))
            }

            item { SectionHeader(stringResource(R.string.settings_data)) }
            item {
                ListRow(stringResource(R.string.transfer_title), subtitle = stringResource(R.string.settings_transfer_sub), icon = Icons.Outlined.Archive, onClick = onTransfer)
                ListRow(stringResource(R.string.qr_send_title), subtitle = stringResource(R.string.settings_qr_send_sub), icon = Icons.Outlined.QrCode2, onClick = onQrSend)
                ListRow(stringResource(R.string.qr_receive_title), subtitle = stringResource(R.string.settings_qr_receive_sub), icon = Icons.Outlined.QrCodeScanner, onClick = onQrReceive)
                ListRow(stringResource(R.string.diagnostics_title), icon = Icons.Outlined.MonitorHeart, onClick = onDiagnostics)
                ListRow(
                    stringResource(R.string.settings_delete_all),
                    subtitle = stringResource(if (recording) R.string.settings_delete_all_recording else R.string.settings_delete_all_sub),
                    icon = Icons.Outlined.DeleteForever,
                    divider = false,
                    onClick = if (recording || deleting) null else ({ codeWord = ""; confirmDelete = true }),
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall, color = mt.ink2, modifier = Modifier.padding(top = 32.dp),
                )
                Caption(stringResource(R.string.settings_local_only), Modifier.padding(top = 4.dp))
            }
        }
    }

    if (confirmDelete) {
        val word = stringResource(R.string.delete_code_word)
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_all_title),
            body = stringResource(R.string.settings_delete_all_body, word),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            confirmEnabled = codeWord.trim().equals(word, ignoreCase = true) && !recording,
            extra = { MTTextField(codeWord, { codeWord = it.take(20) }, stringResource(R.string.settings_delete_type_word, word)) },
            onConfirm = { confirmDelete = false; vm.deleteAll() },
            onDismiss = { confirmDelete = false },
        )
    }
}
