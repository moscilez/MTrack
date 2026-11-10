package com.moscilez.track.data.repo

import com.moscilez.track.data.LocalePrefs
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.data.db.UserProfileEntity
import com.moscilez.track.data.db.UserSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local profile and settings. Settings are a single row; the locale is mirrored to prefs. */
class ProfileRepository(
    private val db: MTrackDatabase,
    private val localePrefs: LocalePrefs,
) {
    val profile: Flow<UserProfileEntity?> = db.profileDao().observe()

    /** Never null for consumers: defaults are returned until the first save. */
    val settings: Flow<UserSettingsEntity> = db.settingsDao().observe().map { it ?: UserSettingsEntity() }

    suspend fun getProfile(): UserProfileEntity? = db.profileDao().get()

    suspend fun getSettings(): UserSettingsEntity = db.settingsDao().get() ?: UserSettingsEntity()

    suspend fun saveProfile(profile: UserProfileEntity) {
        db.profileDao().upsert(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveSettings(settings: UserSettingsEntity) {
        db.settingsDao().upsert(settings)
        localePrefs.language = settings.language
        localePrefs.theme = settings.theme
    }

    suspend fun updateSettings(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        saveSettings(transform(getSettings()))
    }
}
