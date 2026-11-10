package com.moscilez.track.ui.transfer

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.moscilez.track.R
import com.moscilez.track.transfer.ExportSelection
import com.moscilez.track.transfer.ImportPreview
import com.moscilez.track.transfer.ImportReport
import com.moscilez.track.transfer.TransferException
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.SwitchRow
import com.moscilez.track.ui.format.Formatters

/** Maps transfer failures to user-facing text; never shows raw exception messages. */
fun transferErrorText(ctx: Context, e: Throwable): String = ctx.getString(
    when (e) {
        is TransferException.WrongPassword -> R.string.transfer_err_password
        is TransferException.NotAnArchive -> R.string.transfer_err_not_archive
        is TransferException.UnsupportedVersion -> R.string.transfer_err_version
        is TransferException.Corrupted -> R.string.transfer_err_corrupted
        is TransferException.TooLarge -> R.string.transfer_err_too_large
        is TransferException.Storage -> R.string.transfer_err_storage
        is SecurityException -> R.string.transfer_err_access
        else -> R.string.transfer_err_generic
    }
)

@Composable
fun SelectionSwitches(sel: ExportSelection, onChange: (ExportSelection) -> Unit, restricted: Boolean) {
    Column {
        SwitchRow(stringResource(R.string.transfer_sel_activities), sel.activities, { onChange(sel.copy(activities = it, routes = it && sel.routes)) },
            subtitle = if (restricted) stringResource(R.string.transfer_sel_selected_only) else null)
        SwitchRow(stringResource(R.string.transfer_sel_routes), sel.routes && sel.activities, { onChange(sel.copy(routes = it)) })
        SwitchRow(stringResource(R.string.transfer_sel_goals), sel.goals, { onChange(sel.copy(goals = it)) })
        SwitchRow(stringResource(R.string.transfer_sel_profile), sel.profile, { onChange(sel.copy(profile = it)) })
        SwitchRow(stringResource(R.string.transfer_sel_settings), sel.settings, { onChange(sel.copy(settings = it)) })
        SwitchRow(stringResource(R.string.transfer_sel_statistics), sel.statistics, { onChange(sel.copy(statistics = it)) })
    }
}

fun ExportSelection.isEmpty() = !activities && !goals && !profile && !settings && !statistics

@Composable
fun PreviewRows(p: ImportPreview, units: String) {
    val ctx = LocalContext.current
    Column {
        KeyValueRow(stringResource(R.string.transfer_pv_created), Formatters.date(ctx, p.createdAt))
        KeyValueRow(stringResource(R.string.transfer_pv_version), p.formatVersion.toString())
        KeyValueRow(stringResource(R.string.transfer_pv_sessions), p.sessions.toString())
        if (p.firstDate != null && p.lastDate != null) {
            KeyValueRow(stringResource(R.string.transfer_pv_range), Formatters.date(ctx, p.firstDate) + " — " + Formatters.date(ctx, p.lastDate))
        }
        KeyValueRow(stringResource(R.string.transfer_pv_distance), Formatters.distance(ctx, p.totalDistanceM, units))
        KeyValueRow(stringResource(R.string.transfer_pv_routes), p.routes.toString())
        KeyValueRow(stringResource(R.string.transfer_pv_goals), p.goals.toString())
        KeyValueRow(stringResource(R.string.transfer_pv_duplicates), p.duplicates.toString())
        KeyValueRow(
            stringResource(R.string.transfer_pv_profile),
            stringResource(if (p.hasProfile) R.string.transfer_yes else R.string.transfer_no),
        )
        KeyValueRow(
            stringResource(R.string.transfer_pv_settings),
            stringResource(if (p.hasSettings) R.string.transfer_yes else R.string.transfer_no),
        )
    }
}

@Composable
fun ReportRows(r: ImportReport) {
    Column {
        KeyValueRow(stringResource(R.string.transfer_rp_sessions_added), r.sessionsAdded.toString())
        KeyValueRow(stringResource(R.string.transfer_rp_sessions_skipped), r.sessionsSkipped.toString())
        KeyValueRow(stringResource(R.string.transfer_rp_goals_added), r.goalsAdded.toString())
        KeyValueRow(stringResource(R.string.transfer_rp_goals_skipped), r.goalsSkipped.toString())
        KeyValueRow(stringResource(R.string.transfer_rp_routes), r.routesImported.toString())
        if (r.routeErrors > 0) KeyValueRow(stringResource(R.string.transfer_rp_route_errors), r.routeErrors.toString())
        KeyValueRow(stringResource(R.string.transfer_pv_profile), stringResource(if (r.profileImported) R.string.transfer_yes else R.string.transfer_no))
        KeyValueRow(stringResource(R.string.transfer_pv_settings), stringResource(if (r.settingsImported) R.string.transfer_yes else R.string.transfer_no))
    }
}

@Composable
fun sizeText(bytes: Long): String {
    val ctx = LocalContext.current
    return if (bytes < 1024 * 1024) pluralStringResource(R.plurals.size_kb, (bytes / 1024).toInt().coerceAtLeast(1), (bytes / 1024).coerceAtLeast(1))
    else stringResource(R.string.size_mb, Formatters.number(ctx, bytes / 1024.0 / 1024.0, 1))
}
