package com.moscilez.track.ui.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
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
import com.moscilez.track.transfer.ImportMode
import com.moscilez.track.transfer.ImportPreview
import com.moscilez.track.transfer.ImportReport
import com.moscilez.track.transfer.TransferBundle
import com.moscilez.track.transfer.TransferException
import com.moscilez.track.transfer.qr.OfferResult
import com.moscilez.track.transfer.qr.QrAssembler
import com.moscilez.track.transfer.qr.QrCodec
import com.moscilez.track.transfer.qr.QrImage
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ConfirmDialog
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.EmptyState
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.MTTextField
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.start.openAppSettings
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class ScanProgress(val received: Int = 0, val total: Int = 0, val last: OfferResult? = null, val resumed: Boolean = false)

sealed interface QrReceiveStep {
    data object Scanning : QrReceiveStep
    data object NeedCode : QrReceiveStep
    data object Opening : QrReceiveStep
    data class Preview(val bundle: TransferBundle, val preview: ImportPreview) : QrReceiveStep
    data object Importing : QrReceiveStep
    data class Done(val report: ImportReport) : QrReceiveStep
}

class QrReceiveViewModel(private val c: AppContainer) : ViewModel() {
    private val assembler = QrAssembler()
    private val lock = Any()
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress
    private val _step = MutableStateFlow<QrReceiveStep>(QrReceiveStep.Scanning)
    val step: StateFlow<QrReceiveStep> = _step
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error
    val settings: StateFlow<UserSettingsEntity> = c.profileRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettingsEntity())
    private var sinceSave = 0

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { c.transferRepository.qrCache.load() }
            if (cached.isNotEmpty()) synchronized(lock) {
                assembler.restore(cached)
                publish(null, resumed = assembler.received > 0)
                if (assembler.isComplete) _step.value = QrReceiveStep.NeedCode
            }
        }
    }

    private fun publish(last: OfferResult?, resumed: Boolean = _progress.value.resumed) {
        _progress.value = ScanProgress(assembler.received, assembler.total, last, resumed)
    }

    /** Called from the camera analysis thread. */
    fun offer(text: String) {
        if (_step.value != QrReceiveStep.Scanning) return
        var save: List<String>? = null
        synchronized(lock) {
            val r = assembler.offer(text)
            if (r == OfferResult.DUPLICATE && _progress.value.last == OfferResult.DUPLICATE) return
            publish(r)
            if (r == OfferResult.ACCEPTED) {
                sinceSave++
                if (sinceSave >= 10 || assembler.isComplete) { sinceSave = 0; save = assembler.rawFrames }
                if (assembler.isComplete) _step.value = QrReceiveStep.NeedCode
            }
        }
        save?.let { frames -> viewModelScope.launch(Dispatchers.IO) { c.transferRepository.qrCache.save(frames) } }
    }

    fun persist() {
        val frames = synchronized(lock) { assembler.rawFrames }
        if (frames.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { c.transferRepository.qrCache.save(frames) }
    }

    fun restart() {
        synchronized(lock) { assembler.reset(); _progress.value = ScanProgress() }
        _error.value = null
        _step.value = QrReceiveStep.Scanning
        viewModelScope.launch(Dispatchers.IO) { c.transferRepository.qrCache.clear() }
    }

    fun open(code: String) {
        _error.value = null
        _step.value = QrReceiveStep.Opening
        viewModelScope.launch {
            try {
                val blob = synchronized(lock) { assembler.assemble() }
                val (bundle, preview) = c.transferRepository.openQrPackage(blob, code)
                _step.value = QrReceiveStep.Preview(bundle, preview)
            } catch (e: TransferException) {
                _error.value = e; _step.value = QrReceiveStep.NeedCode
            } catch (e: Exception) {
                _error.value = TransferException.Corrupted(e.message ?: "assemble"); _step.value = QrReceiveStep.NeedCode
            }
        }
    }

    fun apply(bundle: TransferBundle, mode: ImportMode) {
        _error.value = null
        _step.value = QrReceiveStep.Importing
        viewModelScope.launch {
            try {
                _step.value = QrReceiveStep.Done(c.transferRepository.importQrBundle(bundle, mode))
            } catch (e: Exception) {
                _error.value = e
                _step.value = QrReceiveStep.NeedCode
            }
        }
    }
}

@Composable
fun QrReceiveScreen(onBack: () -> Unit) {
    val vm = appViewModel { QrReceiveViewModel(it) }
    val step by vm.step.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.persist() }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.qr_receive_title), onBack) }, containerColor = mt.bg) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding).navigationBarsPadding().padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(transferErrorText(ctx, it), color = mt.danger, style = MaterialTheme.typography.bodyMedium) }
                when (val s = step) {
                    QrReceiveStep.Scanning -> ScanPane(progress, vm::offer, vm::restart)
                    QrReceiveStep.NeedCode, QrReceiveStep.Opening -> CodePane(s == QrReceiveStep.Opening, vm::open, vm::restart)
                    is QrReceiveStep.Preview -> ApplyPane(s, settings.units, vm::apply, vm::restart)
                    QrReceiveStep.Importing -> {
                        Caption(stringResource(R.string.transfer_importing))
                        PrimaryButton(stringResource(R.string.transfer_import_go), {}, enabled = false, loading = true)
                    }
                    is QrReceiveStep.Done -> {
                        SectionHeader(stringResource(R.string.transfer_import_done))
                        ReportRows(s.report)
                        PrimaryButton(stringResource(R.string.action_close), onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanPane(progress: ScanProgress, onFrame: (String) -> Unit, onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Caption(stringResource(R.string.qr_receive_intro))
    when {
        !hasCamera -> EmptyState(stringResource(R.string.qr_no_camera_title), stringResource(R.string.qr_no_camera_body))
        !granted -> EmptyState(
            stringResource(R.string.qr_camera_denied_title), stringResource(R.string.qr_camera_denied_body),
            action = stringResource(if (asked) R.string.action_open_settings else R.string.qr_allow_camera),
            onAction = { if (asked) openAppSettings(ctx) else launcher.launch(Manifest.permission.CAMERA) },
        )
        else -> {
            val desc = stringResource(R.string.cd_camera_preview)
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, mt.line, Square).clipToBounds(),
            ) { CameraScanner(onFrame, Modifier.fillMaxSize(), desc) }
        }
    }
    if (progress.total > 0) {
        FlatProgress(progress.received.toFloat() / progress.total, description = stringResource(R.string.qr_receive_progress, progress.received, progress.total))
        KeyValueRow(stringResource(R.string.qr_receive_frames), "${progress.received} / ${progress.total}")
    } else {
        KeyValueRow(stringResource(R.string.qr_receive_frames), stringResource(R.string.qr_receive_waiting))
    }
    when (progress.last) {
        OfferResult.FOREIGN -> Caption(stringResource(R.string.qr_receive_foreign), color = mt.danger)
        OfferResult.CORRUPT, OfferResult.INVALID -> Caption(stringResource(R.string.qr_receive_bad_frame), color = mt.danger)
        else -> Unit
    }
    if (progress.resumed) Caption(stringResource(R.string.qr_receive_resumed))
    if (progress.received > 0) SecondaryButton(stringResource(R.string.qr_receive_restart), onRestart)
}

@Composable
private fun CameraScanner(onFrame: (String) -> Unit, modifier: Modifier, description: String) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(ctx).apply { contentDescription = description; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        QrImage.decode(bytes, plane.rowStride, image.width, image.height)?.let(onFrame)
                    } finally {
                        image.close()
                    }
                }
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> p.availableCameraInfos.firstOrNull()?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
                }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
            } catch (e: Exception) {
                Log.w("MTrack", "camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier)
}

@Composable
private fun CodePane(opening: Boolean, onOpen: (String) -> Unit, onRestart: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val normalized = QrCodec.normalizeCode(code)
    SectionHeader(stringResource(R.string.qr_receive_complete))
    Caption(stringResource(R.string.qr_receive_enter_code))
    MTTextField(
        code, { code = it.take(14) }, stringResource(R.string.qr_receive_code),
        isError = code.length >= 10 && normalized == null,
        supporting = stringResource(R.string.qr_receive_code_hint),
    )
    PrimaryButton(
        stringResource(R.string.transfer_check_archive), { normalized?.let(onOpen) },
        enabled = normalized != null && !opening, loading = opening,
    )
    SecondaryButton(stringResource(R.string.qr_receive_restart), onRestart)
}

@Composable
private fun ApplyPane(s: QrReceiveStep.Preview, units: String, onApply: (TransferBundle, ImportMode) -> Unit, onCancel: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(ImportMode.MERGE) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.transfer_preview))
    PreviewRows(s.preview, units)
    SectionHeader(stringResource(R.string.transfer_mode))
    Segmented(
        ImportMode.entries.toList(), mode,
        { stringResource(if (it == ImportMode.MERGE) R.string.transfer_mode_merge else R.string.transfer_mode_replace) },
        { mode = it },
    )
    Caption(stringResource(if (mode == ImportMode.MERGE) R.string.transfer_mode_merge_sub else R.string.transfer_mode_replace_sub))
    PrimaryButton(stringResource(R.string.transfer_import_go), { if (mode == ImportMode.REPLACE) confirm = true else onApply(s.bundle, mode) })
    SecondaryButton(stringResource(R.string.action_cancel), onCancel)
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.transfer_replace_title), stringResource(R.string.transfer_replace_body),
            stringResource(R.string.transfer_mode_replace),
            onConfirm = { confirm = false; onApply(s.bundle, ImportMode.REPLACE) },
            onDismiss = { confirm = false }, danger = true,
        )
    }
}
