package com.moscilez.track

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.moscilez.track.data.LocalePrefs
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.data.repo.ActivityRepository
import com.moscilez.track.data.repo.GoalRepository
import com.moscilez.track.data.repo.ProfileRepository
import com.moscilez.track.tracking.TrackingStateHolder
import com.moscilez.track.transfer.TransferRepository
import com.moscilez.track.work.MaintenanceWorker
import org.osmdroid.config.Configuration
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency container: a handful of singletons does not justify a DI framework. */
class AppContainer(context: Context) {
    val localePrefs = LocalePrefs(context)
    val database: MTrackDatabase = MTrackDatabase.build(context)
    val profileRepository = ProfileRepository(database, localePrefs)
    val activityRepository = ActivityRepository(database)
    val goalRepository = GoalRepository(database)
    val trackingState = TrackingStateHolder()
    val transferRepository = TransferRepository(context, database, profileRepository)
}

class MTrackApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Map tiles: identify the app to the OSM tile servers and keep the cache in app-private storage.
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MaintenanceWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

val Context.container: AppContainer get() = (applicationContext as MTrackApp).container
