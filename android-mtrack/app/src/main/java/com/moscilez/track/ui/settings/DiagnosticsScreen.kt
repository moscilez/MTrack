package com.moscilez.track.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.BuildConfig
import com.moscilez.track.R
import com.moscilez.track.data.db.ExportMetadataEntity
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.BoundedLazyColumn
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.format.Formatters
import com.moscilez.track.ui.record.gpsLabel
import com.moscilez.track.ui.start.GpsReadiness
import com.moscilez.track.ui.start.batteryPercent
import com.moscilez.track.ui.start.gpsReadiness
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DbCounts(val sessions: Int, val points: Int)

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    val tracking = c.trackingState.state
    val operations: StateFlow<List<ExportMetadataEntity>> = c.transferRepository.recentOperations
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _counts = MutableStateFlow<DbCounts?>(null)
    val counts: StateFlow<DbCounts?> = _counts
    private val _countError = MutableStateFlow(false)
    val countError: StateFlow<Boolean> = _countError

    fun refresh() {
        viewModelScope.launch {
            runCatching { DbCounts(c.activityRepository.sessionCount(), c.activityRepository.pointCount()) }
                .onSuccess { _counts.value = it; _countError.value = false }
                .onFailure { _countError.value = true }
        }
    }

    init { refresh() }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm = appViewModel { DiagnosticsViewModel(it) }
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val tracking by vm.tracking.collectAsStateWithLifecycle()
    val ops by vm.operations.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val countError by vm.countError.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; vm.refresh() }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val yes = stringResource(R.string.diag_granted)
    val no = stringResource(R.string.diag_denied)

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.diagnostics_title), onBack) }, containerColor = mt.bg) { inner ->
        Box(Modifier.padding(inner)) {
            val readiness = remember(tick) { gpsReadiness(ctx) }
            val battery = remember(tick) { batteryPercent(ctx) }
            val perms = remember(tick) {
                listOf(
                    granted(Manifest.permission.ACCESS_FINE_LOCATION),
                    granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                    if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else true,
                    granted(Manifest.permission.CAMERA),
                )
            }
            BoundedLazyColumn {
                item { Caption(stringResource(R.string.diag_intro), Modifier.padding(bottom = 8.dp)) }
                item { SectionHeader(stringResource(R.string.diag_permissions)) }
                item {
                    KeyValueRow(stringResource(R.string.diag_perm_fine), if (perms[0]) yes else no, valueColor = if (perms[0]) mt.ink else mt.danger)
                    KeyValueRow(stringResource(R.string.diag_perm_coarse), if (perms[1]) yes else no)
                    KeyValueRow(stringResource(R.string.diag_perm_notifications), if (perms[2]) yes else no)
                    KeyValueRow(stringResource(R.string.diag_perm_camera), if (perms[3]) yes else no)
                }
                item { SectionHeader(stringResource(R.string.diag_device)) }
                item {
                    KeyValueRow(
                        stringResource(R.string.diag_gps),
                        stringResource(
                            when (readiness) {
                                GpsReadiness.READY -> R.string.start_gps_ready
                                GpsReadiness.APPROXIMATE -> R.string.start_gps_approx
                                GpsReadiness.NO_PERMISSION -> R.string.start_gps_no_permission
                                GpsReadiness.DISABLED -> R.string.start_gps_disabled
                            }
                        ),
                        valueColor = if (readiness == GpsReadiness.READY) mt.ink else mt.danger,
                    )
                    KeyValueRow(
                        stringResource(R.string.diag_battery),
                        battery?.let { stringResource(R.string.start_battery, it) } ?: stringResource(R.string.start_battery_unknown),
                    )
                    KeyValueRow(stringResource(R.string.diag_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    KeyValueRow(stringResource(R.string.diag_model), "${Build.MANUFACTURER} ${Build.MODEL}")
                }
                item { SectionHeader(stringResource(R.string.diag_tracking)) }
                item {
                    KeyValueRow(stringResource(R.string.diag_phase), tracking.phase.name)
                    if (tracking.isActive) {
                        KeyValueRow(stringResource(R.string.diag_signal), gpsLabel(tracking.gps))
                        KeyValueRow(
                            stringResource(R.string.diag_accuracy),
                            tracking.accuracy?.let { Formatters.number(ctx, it, 0) + " " + stringResource(R.string.unit_m) } ?: "—",
                        )
                        KeyValueRow(stringResource(R.string.diag_points_accepted), tracking.acceptedPoints.toString())
                        KeyValueRow(stringResource(R.string.diag_points_rejected), tracking.rejectedPoints.toString())
                    }
                }
                item { SectionHeader(stringResource(R.string.diag_storage)) }
                item {
                    when {
                        countError -> Caption(stringResource(R.string.diag_count_error), color = mt.danger)
                        counts == null -> Caption(stringResource(R.string.diag_loading))
                        else -> {
                            KeyValueRow(stringResource(R.string.diag_sessions), counts!!.sessions.toString())
                            KeyValueRow(stringResource(R.string.diag_points), counts!!.points.toString())
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.diag_operations)) }
                if (ops.isEmpty()) {
                    item { Caption(stringResource(R.string.diag_operations_empty)) }
                } else {
                    items(ops.size) { i ->
                        val op = ops[i]
                        KeyValueRow(
                            opLabel(op.kind) + " · " + Formatters.date(ctx, op.timestamp),
                            if (op.success) stringResource(R.string.diag_op_ok, op.itemCount) else stringResource(R.string.diag_op_failed),
                            valueColor = if (op.success) mt.ink else mt.danger,
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.diag_app)) }
                item {
                    KeyValueRow(stringResource(R.string.diag_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Caption(stringResource(R.string.settings_local_only), Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun opLabel(kind: String): String = stringResource(
    when (kind) {
        "ARCHIVE_EXPORT" -> R.string.op_archive_export
        "ARCHIVE_IMPORT" -> R.string.op_archive_import
        "QR_EXPORT" -> R.string.op_qr_export
        "QR_IMPORT" -> R.string.op_qr_import
        else -> R.string.op_other
    }
)
