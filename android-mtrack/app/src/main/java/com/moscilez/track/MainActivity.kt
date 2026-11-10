package com.moscilez.track

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moscilez.track.data.LocalePrefs
import com.moscilez.track.ui.MTrackRoot
import com.moscilez.track.ui.theme.MTrackTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = container.localePrefs
        val initialTheme = prefs.theme
        val dark = when (initialTheme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val themeFlow = container.profileRepository.settings.map { it.theme }
        setContent {
            val theme by themeFlow.collectAsStateWithLifecycle(initialTheme)
            MTrackTheme(theme) { MTrackRoot() }
        }
    }
}
