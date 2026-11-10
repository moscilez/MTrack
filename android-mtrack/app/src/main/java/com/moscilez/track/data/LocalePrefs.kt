package com.moscilez.track.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Small synchronous mirror of language/theme, needed in attachBaseContext before Room is
 * available. Room's user_settings row remains the source of truth.
 */
class LocalePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var theme: String
        get() = prefs.getString(KEY_THEME, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val NAME = "mtrack_locale"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"

        /** Wraps a context with the user-selected locale ("system" keeps the device locale). */
        fun wrap(base: Context): Context {
            val lang = LocalePrefs(base).language
            if (lang == "system") return base
            val locale = Locale.forLanguageTag(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
