package com.moscilez.track.ui.transfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.transfer.ArchiveInspection
import com.moscilez.track.transfer.ExportResult
import com.moscilez.track.transfer.ExportSelection
import com.moscilez.track.transfer.ImportMode
import com.moscilez.track.transfer.ImportReport
import com.moscilez.track.transfer.TransferFormat
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface ExportState {
    data object Idle : ExportState
    data object Working : ExportState
    data class Done(val result: ExportResult) : ExportState
    data class Failed(val error: Throwable) : ExportState
}

sealed interface ImportState {
    data object Idle : ImportState
    data class NeedPassword(val uri: Uri) : ImportState
    data class Inspecting(val uri: Uri) : ImportState
    data class Preview(val inspection: ArchiveInspection) : ImportState
    data object Importing : ImportState
    data class Done(val report: ImportReport) : ImportState
    data class Failed(val error: Throwable, val uri: Uri?) : ImportState
}

class TransferViewModel(private val c: AppContainer, sessionIds: List<Long>) : ViewModel() {
    val settings: StateFlow<UserSettingsEntity> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettingsEntity())
    val selection = MutableStateFlow(ExportSelection(sessionIds = sessionIds.ifEmpty { null }))
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export
    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import

    fun exportTo(uri: Uri, password: String) {
        _export.value = ExportState.Working
        viewModelScope.launch {
            val chars = password.toCharArray()
            _export.value = try {
                ExportState.Done(c.transferRepository.exportArchive(uri, chars, selection.value))
            } catch (e: Exception) {
                ExportState.Failed(e)
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun resetExport() { _export.value = ExportState.Idle }

    fun picked(uri: Uri) { _import.value = ImportState.NeedPassword(uri) }

    fun inspect(uri: Uri, password: String) {
        _import.value = ImportState.Inspecting(uri)
        viewModelScope.launch {
            val chars = password.toCharArray()
            _import.value = try {
                ImportState.Preview(c.transferRepository.inspectArchive(uri, chars))
            } catch (e: Exception) {
                ImportState.Failed(e, uri)
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun apply(inspection: ArchiveInspection, mode: ImportMode) {
        _import.value = ImportState.Importing
        viewModelScope.launch {
            _import.value = try {
                ImportState.Done(c.transferRepository.importArchive(inspection, mode))
            } catch (e: Exception) {
                ImportState.Failed(e, null)
            }
        }
    }

    fun resetImport() { _import.value = ImportState.Idle }
}

private enum class TransferTab { EXPORT, IMPORT }

@Composable
fun TransferScreen(sessionIds: List<Long>, onBack: () -> Unit) {
    val key = "transfer-" + sessionIds.joinToString(",")
    val vm = appViewModel(key) { TransferViewModel(it, sessionIds) }
    var tab by rememberSaveable { mutableStateOf(TransferTab.EXPORT) }
    val mt = LocalMT.current

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.transfer_title), onBack) }, containerColor = mt.bg) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding).navigationBarsPadding().padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Segmented(
                    TransferTab.entries.toList(), tab,
                    { stringResource(if (it == TransferTab.EXPORT) R.string.transfer_tab_export else R.string.transfer_tab_import) },
                    { tab = it },
                )
                when (tab) {
                    TransferTab.EXPORT -> ExportPane(vm, restricted = sessionIds.isNotEmpty())
                    TransferTab.IMPORT -> ImportPane(vm)
                }
            }
        }
    }
}

@Composable
private fun ExportPane(vm: TransferViewModel, restricted: Boolean) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val sel by vm.selection.collectAsStateWithLifecycle()
    val state by vm.export.collectAsStateWithLifecycle()
    var pass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val tooShort = pass.isNotEmpty() && pass.length < TransferFormat.MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != pass
    val valid = pass.length >= TransferFormat.MIN_PASSWORD && pass == confirm && !sel.isEmpty()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportTo(uri, pass)
    }

    when (val s = state) {
        is ExportState.Done -> {
            SectionHeader(stringResource(R.string.transfer_export_done))
            KeyValueRow(stringResource(R.string.transfer_pv_sessions), s.result.sessions.toString())
            KeyValueRow(stringResource(R.string.transfer_pv_routes), s.result.routes.toString())
            KeyValueRow(stringResource(R.string.transfer_size), sizeText(s.result.bytes))
            KeyValueRow(stringResource(R.string.transfer_checksum), s.result.checksum.take(16))
            Caption(stringResource(R.string.transfer_export_done_body))
            SecondaryButton(stringResource(R.string.action_close), { pass = ""; confirm = ""; vm.resetExport() })
            return
        }
        is ExportState.Failed -> {
            Text(transferErrorText(ctx, s.error), color = mt.danger, style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }

    Caption(stringResource(R.string.transfer_export_intro))
    SectionHeader(stringResource(R.string.transfer_what))
    SelectionSwitches(sel, { vm.selection.value = it }, restricted)
    SectionHeader(stringResource(R.string.transfer_password))
    MTTextField(pass, { pass = it.take(128) }, stringResource(R.string.transfer_password), password = true,
        isError = tooShort, supporting = stringResource(R.string.transfer_password_hint, TransferFormat.MIN_PASSWORD))
    MTTextField(confirm, { confirm = it.take(128) }, stringResource(R.string.transfer_password_confirm), password = true,
        isError = mismatch, supporting = if (mismatch) stringResource(R.string.transfer_password_mismatch) else null)
    Caption(stringResource(R.string.transfer_password_warning))
    Spacer(Modifier.height(4.dp))
    PrimaryButton(
        stringResource(R.string.transfer_create_archive),
        { launcher.launch("mtrack-${LocalDate.now()}.zip") },
        enabled = valid && state !is ExportState.Working,
        loading = state is ExportState.Working,
    )
}

@Composable
private fun ImportPane(vm: TransferViewModel) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val state by vm.import.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pass by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ImportMode.MERGE) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pass = ""; vm.picked(uri) }
    }

    when (val s = state) {
        ImportState.Idle -> {
            Caption(stringResource(R.string.transfer_import_intro))
            PrimaryButton(stringResource(R.string.transfer_pick_file), { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
        }
        is ImportState.NeedPassword, is ImportState.Inspecting, is ImportState.Failed -> {
            val uri = when (s) {
                is ImportState.NeedPassword -> s.uri
                is ImportState.Inspecting -> s.uri
                is ImportState.Failed -> s.uri
                else -> null
            }
            if (s is ImportState.Failed) Text(transferErrorText(ctx, s.error), color = mt.danger, style = MaterialTheme.typography.bodyMedium)
            if (uri != null) {
                Caption(stringResource(R.string.transfer_enter_password))
                MTTextField(pass, { pass = it.take(128) }, stringResource(R.string.transfer_password), password = true)
                PrimaryButton(
                    stringResource(R.string.transfer_check_archive),
                    { vm.inspect(uri, pass) },
                    enabled = pass.isNotEmpty() && s !is ImportState.Inspecting,
                    loading = s is ImportState.Inspecting,
                )
            }
            SecondaryButton(stringResource(R.string.transfer_pick_other), { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
        }
        is ImportState.Preview -> {
            SectionHeader(stringResource(R.string.transfer_preview))
            PreviewRows(s.inspection.preview, settings.units)
            SectionHeader(stringResource(R.string.transfer_mode))
            Segmented(
                ImportMode.entries.toList(), mode,
                { stringResource(if (it == ImportMode.MERGE) R.string.transfer_mode_merge else R.string.transfer_mode_replace) },
                { mode = it },
            )
            Caption(stringResource(if (mode == ImportMode.MERGE) R.string.transfer_mode_merge_sub else R.string.transfer_mode_replace_sub))
            PrimaryButton(stringResource(R.string.transfer_import_go), {
                if (mode == ImportMode.REPLACE) confirmReplace = true else vm.apply(s.inspection, mode)
            })
            SecondaryButton(stringResource(R.string.action_cancel), { pass = ""; vm.resetImport() })
            if (confirmReplace) {
                ConfirmDialog(
                    stringResource(R.string.transfer_replace_title), stringResource(R.string.transfer_replace_body),
                    stringResource(R.string.transfer_mode_replace),
                    onConfirm = { confirmReplace = false; vm.apply(s.inspection, ImportMode.REPLACE) },
                    onDismiss = { confirmReplace = false }, danger = true,
                )
            }
        }
        ImportState.Importing -> {
            Caption(stringResource(R.string.transfer_importing))
            PrimaryButton(stringResource(R.string.transfer_import_go), {}, enabled = false, loading = true)
        }
        is ImportState.Done -> {
            SectionHeader(stringResource(R.string.transfer_import_done))
            ReportRows(s.report)
            SecondaryButton(stringResource(R.string.action_close), { pass = ""; vm.resetImport() })
        }
    }
}
