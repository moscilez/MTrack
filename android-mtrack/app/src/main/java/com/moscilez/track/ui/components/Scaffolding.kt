package com.moscilez.track.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moscilez.track.R
import com.moscilez.track.ui.theme.LocalMT
import kotlinx.coroutines.delay

val ScreenPadding = 24.dp

/** Brand header shown on every tab screen: wordmark + tagline, profile and settings. */
@Composable
fun AppHeader(onSettings: () -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall, color = LocalMT.current.ink2)
        }
        IconButton(onClick = onProfile) { Icon(Icons.Outlined.AccountCircle, stringResource(R.string.cd_profile)) }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.cd_settings)) }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = LocalMT.current.ink2)
    }
}

/** LazyColumn centred and capped at [ContentMaxWidth], with the standard side padding. */
@Composable
fun BoundedLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 32.dp),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(horizontal = ScreenPadding),
            state = state,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** Wall clock that ticks every [intervalMs] while composed; drives live timers. */
@Composable
fun rememberNow(intervalMs: Long = 1000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(intervalMs - (value % intervalMs).coerceAtMost(intervalMs - 1))
    }
}

@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
