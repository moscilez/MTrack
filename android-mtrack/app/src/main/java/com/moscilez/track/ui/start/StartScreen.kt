package com.moscilez.track.ui.start

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.TripType
import com.moscilez.track.tracking.TrackingService
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.AppHeader
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.Hairline
import com.moscilez.track.ui.components.ListRow
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.ScreenTitle
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.icon
import com.moscilez.track.ui.components.label
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

class StartViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<UserSettingsEntity?> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun preferredType(): ActivityType? = c.profileRepository.getProfile()?.preferredType?.let { ActivityType.from(it) }

    fun acceptCarWarning() = viewModelScope.launch { c.profileRepository.updateSettings { it.copy(carWarningAccepted = true) } }

    fun toggleUnits() = viewModelScope.launch {
        c.profileRepository.updateSettings { it.copy(units = if (it.units == "IMPERIAL") "METRIC" else "IMPERIAL") }
    }
}

enum class GpsReadiness { READY, APPROXIMATE, NO_PERMISSION, DISABLED }

fun gpsReadiness(context: Context): GpsReadiness {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return GpsReadiness.NO_PERMISSION
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    if (!enabled) return GpsReadiness.DISABLED
    return if (fine) GpsReadiness.READY else GpsReadiness.APPROXIMATE
}

fun batteryPercent(context: Context): Int? =
    (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

fun autoTitle(context: Context, type: ActivityType, hour: Int = LocalTime.now().hour): String {
    val part = context.getString(
        when (hour) {
            in 5..11 -> R.string.daypart_morning
            in 12..17 -> R.string.daypart_day
            in 18..22 -> R.string.daypart_evening
            else -> R.string.daypart_night
        }
    )
    return context.getString(R.string.start_auto_title, context.getString(type.label()), part)
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun StartScreen(onBack: () -> Unit, onStarted: () -> Unit, onSettings: () -> Unit, onProfile: () -> Unit) {
    val vm = appViewModel { StartViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current

    var type by rememberSaveable { mutableStateOf<ActivityType?>(null) }
    var trip by rememberSaveable { mutableStateOf(TripType.PERSONAL) }
    var limitText by rememberSaveable { mutableStateOf("") }
    var gps by rememberSaveable { mutableStateOf(GpsReadiness.NO_PERMISSION) }
    var battery by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCarWarning by rememberSaveable { mutableStateOf(false) }
    var showDenied by rememberSaveable { mutableStateOf(false) }
    var showGpsOff by rememberSaveable { mutableStateOf(false) }
    var tick by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { if (type == null) type = vm.preferredType() ?: ActivityType.RUN }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        gps = gpsReadiness(ctx)
        battery = batteryPercent(ctx)
    }
    LaunchedEffect(tick) {
        gps = gpsReadiness(ctx)
        battery = batteryPercent(ctx)
    }

    val selected = type ?: ActivityType.RUN
    val units = settings?.units ?: "METRIC"

    fun launchService() {
        val limitKmh = limitText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 5.0..400.0 }?.let {
            if (Formatters.imperial(units)) it * Formatters.M_PER_MILE / 1000.0 else it
        }
        TrackingService.command(ctx, TrackingService.ACTION_START) {
            putExtra(TrackingService.EXTRA_TYPE, selected.name)
            putExtra(TrackingService.EXTRA_TITLE, autoTitle(ctx, selected))
            if (selected.isVehicle) {
                putExtra(TrackingService.EXTRA_TRIP, trip.name)
                if (limitKmh != null) putExtra(TrackingService.EXTRA_LIMIT, limitKmh)
            }
        }
        onStarted()
    }

    fun proceedAfterPermissions() {
        if (gpsReadiness(ctx) == GpsReadiness.DISABLED) showGpsOff = true else launchService()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notifications are optional: recording works without them.
        proceedAfterPermissions()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        val granted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            showDenied = true
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else proceedAfterPermissions()
    }

    fun requestAndStart() {
        if (gpsReadiness(ctx) == GpsReadiness.NO_PERMISSION) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else proceedAfterPermissions()
    }

    fun onStartClick() {
        if (selected.isVehicle && settings?.carWarningAccepted != true) showCarWarning = true else requestAndStart()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BoundedLazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            item { AppHeader(onSettings, onProfile) }
            item { ScreenTitle(stringResource(R.string.start_title), stringResource(R.string.start_subtitle)) }
            items(ActivityType.entries.toList(), key = { it.name }) { t ->
                ListRow(
                    title = stringResource(t.label()),
                    icon = t.icon(),
                    selected = t == selected,
                    onClick = { type = t },
                )
            }
            if (selected.isVehicle) {
                item {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.start_trip_type), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Segmented(
                            TripType.entries.toList(), trip,
                            label = { stringResource(tripLabel(it)) },
                            onSelect = { trip = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        MTTextField(
                            limitText, { limitText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) },
                            label = stringResource(R.string.start_speed_limit, Formatters.speedUnit(ctx, units)),
                            keyboardType = KeyboardType.Decimal,
                            supporting = stringResource(R.string.start_speed_limit_hint),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)); Hairline() }
            item {
                val gpsText = when (gps) {
                    GpsReadiness.READY -> stringResource(R.string.start_gps_ready)
                    GpsReadiness.APPROXIMATE -> stringResource(R.string.start_gps_approx)
                    GpsReadiness.NO_PERMISSION -> stringResource(R.string.start_gps_no_permission)
                    GpsReadiness.DISABLED -> stringResource(R.string.start_gps_disabled)
                }
                ListRow(
                    title = gpsText,
                    icon = Icons.Filled.LocationOn,
                    onClick = {
                        when (gps) {
                            GpsReadiness.NO_PERMISSION -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            GpsReadiness.DISABLED -> ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            GpsReadiness.APPROXIMATE -> openAppSettings(ctx)
                            GpsReadiness.READY -> tick++
                        }
                    },
                )
            }
            item {
                ListRow(
                    title = battery?.let { stringResource(R.string.start_battery, it) } ?: stringResource(R.string.start_battery_unknown),
                    subtitle = if ((battery ?: 100) < 15) stringResource(R.string.start_battery_low) else null,
                    icon = Icons.Filled.BatteryStd,
                    chevron = false,
                )
            }
            item {
                ListRow(
                    title = stringResource(if (Formatters.imperial(units)) R.string.start_units_mi else R.string.start_units_km),
                    subtitle = stringResource(R.string.start_units_hint),
                    icon = Icons.Filled.Straighten,
                    divider = false,
                    onClick = { vm.toggleUnits() },
                )
            }
        }
        Box(Modifier.fillMaxWidth().background(mt.bg), contentAlignment = Alignment.Center) {
            PrimaryButton(
                stringResource(R.string.start_go),
                ::onStartClick,
                Modifier.widthIn(max = ContentMaxWidth).padding(horizontal = ScreenPadding, vertical = 12.dp),
                enabled = settings != null,
            )
        }
    }

    if (showCarWarning) {
        ConfirmDialog(
            title = stringResource(R.string.car_warning_title),
            body = stringResource(R.string.car_warning_body),
            confirm = stringResource(R.string.car_warning_accept),
            onConfirm = { showCarWarning = false; vm.acceptCarWarning(); requestAndStart() },
            onDismiss = { showCarWarning = false },
        )
    }
    if (showDenied) {
        ConfirmDialog(
            title = stringResource(R.string.perm_location_title),
            body = stringResource(R.string.perm_location_body),
            confirm = stringResource(R.string.action_open_settings),
            onConfirm = { showDenied = false; openAppSettings(ctx) },
            onDismiss = { showDenied = false },
        )
    }
    if (showGpsOff) {
        ConfirmDialog(
            title = stringResource(R.string.gps_off_title),
            body = stringResource(R.string.gps_off_body),
            confirm = stringResource(R.string.action_open_settings),
            onConfirm = { showGpsOff = false; ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            onDismiss = { showGpsOff = false },
            extra = {
                androidx.compose.material3.TextButton(onClick = { showGpsOff = false; launchService() }) {
                    Text(stringResource(R.string.gps_off_start_anyway), color = mt.accent)
                }
            },
        )
    }
}

fun tripLabel(t: TripType): Int = when (t) {
    TripType.PERSONAL -> R.string.trip_personal
    TripType.WORK -> R.string.trip_work
    TripType.TRAVEL -> R.string.trip_travel
}

