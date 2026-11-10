package com.moscilez.track.ui.transfer

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moscilez.track.AppContainer
import com.moscilez.track.R
import com.moscilez.track.transfer.ExportSelection
import com.moscilez.track.transfer.TransferRepository
import com.moscilez.track.transfer.qr.QrCodec
import com.moscilez.track.transfer.qr.QrImage
import com.moscilez.track.ui.appViewModel
import com.moscilez.track.ui.components.Caption
import com.moscilez.track.ui.components.ContentMaxWidth
import com.moscilez.track.ui.components.DetailTopBar
import com.moscilez.track.ui.components.FlatProgress
import com.moscilez.track.ui.components.KeepScreenOn
import com.moscilez.track.ui.components.KeyValueRow
import com.moscilez.track.ui.components.PrimaryButton
import com.moscilez.track.ui.components.ScreenPadding
import com.moscilez.track.ui.components.SecondaryButton
import com.moscilez.track.ui.components.SectionHeader
import com.moscilez.track.ui.components.Segmented
import com.moscilez.track.ui.components.Square
import com.moscilez.track.ui.components.findActivity
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface QrSendState {
    data object Setup : QrSendState
    data object Preparing : QrSendState
    data class Showing(val pkg: TransferRepository.QrPackage) : QrSendState
    data class Failed(val error: Throwable) : QrSendState
}

class QrSendViewModel(private val c: AppContainer) : ViewModel() {
    val selection = MutableStateFlow(ExportSelection(profile = false, settings = false, statistics = false))
    private val _state = MutableStateFlow<QrSendState>(QrSendState.Setup)
    val state: StateFlow<QrSendState> = _state

    fun prepare() {
        _state.value = QrSendState.Preparing
        viewModelScope.launch {
            _state.value = try {
                QrSendState.Showing(c.transferRepository.createQrPackage(selection.value))
            } catch (e: Exception) {
                QrSendState.Failed(e)
            }
        }
    }

    fun back() { _state.value = QrSendState.Setup }
}

private enum class QrSpeed(val ms: Long) { SLOW(700), NORMAL(400), FAST(220) }

@Composable
fun QrSendScreen(onBack: () -> Unit) {
    val vm = appViewModel { QrSendViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mt = LocalMT.current

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.qr_send_title), onBack) }, containerColor = mt.bg) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding).navigationBarsPadding().padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = state) {
                    is QrSendState.Showing -> Broadcast(s.pkg, onStop = vm::back)
                    else -> {
                        Caption(stringResource(R.string.qr_send_intro))
                        SectionHeader(stringResource(R.string.transfer_what))
                        SelectionSwitches(sel, { vm.selection.value = it }, restricted = false)
                        if (s is QrSendState.Failed) Text(transferErrorText(ctx, s.error), color = mt.danger, style = MaterialTheme.typography.bodyMedium)
                        Caption(stringResource(R.string.qr_send_routes_hint))
                        PrimaryButton(
                            stringResource(R.string.qr_send_create), vm::prepare,
                            enabled = !sel.isEmpty() && s !is QrSendState.Preparing,
                            loading = s is QrSendState.Preparing,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Broadcast(pkg: TransferRepository.QrPackage, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val mt = LocalMT.current
    KeepScreenOn()
    DisposableEffect(Unit) {
        val window = ctx.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    var speed by rememberSaveable { mutableStateOf(QrSpeed.NORMAL) }
    var index by remember { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pkg, speed) {
        while (true) {
            val i = index % pkg.frames.size
            bitmap = withContext(Dispatchers.Default) { QrImage.render(pkg.frames[i]) }
            delay(speed.ms)
            index = (i + 1) % pkg.frames.size
        }
    }

    Text(stringResource(R.string.qr_send_code_label), style = Num.label, color = mt.ink2)
    Text(QrCodec.formatCode(pkg.code), style = Num.l, color = mt.ink, modifier = Modifier.semantics { contentDescription = pkg.code.toList().joinToString(" ") })
    Caption(stringResource(R.string.qr_send_code_hint))
    val desc = stringResource(R.string.cd_qr_frame, index + 1, pkg.frames.size)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, Square).border(1.dp, mt.line, Square).padding(16.dp)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
    }
    FlatProgress((index + 1f) / pkg.frames.size)
    KeyValueRow(stringResource(R.string.qr_send_frame), "${index + 1} / ${pkg.frames.size}")
    KeyValueRow(stringResource(R.string.transfer_pv_sessions), pkg.sessions.toString())
    KeyValueRow(stringResource(R.string.transfer_size), sizeText(pkg.bytes.toLong()))
    if (pkg.frames.size > QrCodec.MAX_FRAMES) {
        Text(stringResource(R.string.qr_send_too_many, QrCodec.MAX_FRAMES), color = mt.danger, style = MaterialTheme.typography.bodyMedium)
    }
    SectionHeader(stringResource(R.string.qr_send_speed))
    Segmented(
        QrSpeed.entries.toList(), speed,
        { stringResource(when (it) { QrSpeed.SLOW -> R.string.qr_speed_slow; QrSpeed.NORMAL -> R.string.qr_speed_normal; QrSpeed.FAST -> R.string.qr_speed_fast }) },
        { speed = it },
    )
    Caption(stringResource(R.string.qr_send_loop_hint))
    SecondaryButton(stringResource(R.string.qr_send_stop), onStop)
}
