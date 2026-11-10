package com.moscilez.track.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteFullException
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.moscilez.track.MainActivity
import com.moscilez.track.R
import com.moscilez.track.container
import com.moscilez.track.data.db.ActivityPointEntity
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.UserSettingsEntity
import com.moscilez.track.data.repo.toTrackPoint
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.SessionStatus
import com.moscilez.track.domain.track.FilterResult
import com.moscilez.track.domain.track.GpsFilter
import com.moscilez.track.domain.track.SessionAccumulator
import com.moscilez.track.domain.track.TrackPoint
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.ui.format.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns GPS recording. It is the only writer of live tracking
 * state; the UI observes [TrackingStateHolder] and sends commands via intents, so the
 * recording survives Activity recreation, backgrounding and (via START_STICKY + Room
 * state) process death.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private lateinit var fused: FusedLocationProviderClient
    private val holder by lazy { container.trackingState }
    private val repo by lazy { container.activityRepository }

    private var session: ActivitySessionEntity? = null
    private var settings: UserSettingsEntity = UserSettingsEntity()
    private var type: ActivityType = ActivityType.RUN
    private var filter: GpsFilter? = null
    private var accumulator: SessionAccumulator? = null
    private val buffer = ArrayList<ActivityPointEntity>(64)
    private var lastFlush = 0L
    private var segment = 0
    private var slowSince: Long? = null
    private var lastNotify = 0L
    private var locationActive = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.toList()
            scope.launch { mutex.withLock { locations.forEach { onFix(it) } } }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                holder.update { it.copy(gps = if (gpsEnabled()) GpsState.SEARCHING else GpsState.DISABLED) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately to satisfy the 5 second rule.
        if (!startInForeground()) {
            holder.update { it.copy(phase = TrackingPhase.IDLE, error = TrackingError.PERMISSION_DENIED) }
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> scope.launch {
                mutex.withLock {
                    start(
                        ActivityType.from(intent.getStringExtra(EXTRA_TYPE)),
                        intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        intent.getStringExtra(EXTRA_TRIP),
                        intent.getDoubleExtra(EXTRA_LIMIT, -1.0).takeIf { it > 0 },
                    )
                }
            }
            ACTION_PAUSE -> scope.launch { mutex.withLock { pause(auto = false) } }
            ACTION_RESUME -> scope.launch { mutex.withLock { resume() } }
            ACTION_STOP -> scope.launch { mutex.withLock { stop() } }
            // Null intent = system restarted us after process death; ACTION_RESTORE = user chose to continue.
            else -> scope.launch { mutex.withLock { restore() } }
        }
        return START_STICKY
    }

    private fun startInForeground(): Boolean {
        if (!hasLocationPermission()) return false
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(holder.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun loadConfig() {
        settings = container.profileRepository.getSettings()
    }

    private suspend fun start(type: ActivityType, title: String, trip: String?, limit: Double?) {
        if (session != null) return
        loadConfig()
        this.type = type
        holder.update { LiveTracking(phase = TrackingPhase.STARTING, type = type, speedLimitKmh = limit) }
        val id = try {
            repo.create(type, title, trip, limit)
        } catch (e: SQLiteFullException) {
            fail(TrackingError.STORAGE_FULL); return
        } catch (e: Exception) {
            fail(TrackingError.DATABASE); return
        }
        session = repo.get(id)
        segment = 0
        initPipeline()
        val s = session!!
        holder.update {
            it.copy(phase = TrackingPhase.RECORDING, sessionId = id, accumulatedMs = 0, resumedAt = s.resumedAt)
        }
        feedback()
        requestLocation()
    }

    private fun initPipeline() {
        val threshold = (session?.speedLimitKmh ?: settings.carSpeedThresholdKmh).takeIf { type.isVehicle }?.div(3.6)
        filter = GpsFilter(type, maxAccuracyM = settings.maxAccuracyM, minDistanceM = settings.minDistanceM)
        accumulator = SessionAccumulator(type, overSpeedThreshold = threshold)
    }

    /** Rebuilds live state from Room after process death or when the user resumes a saved recording. */
    private suspend fun restore() {
        if (session != null) return
        loadConfig()
        val s = repo.getUnfinished()
        if (s == null) {
            stopSelf(); return
        }
        session = s
        type = ActivityType.from(s.type)
        initPipeline()
        holder.update { LiveTracking(phase = TrackingPhase.STARTING, type = type, sessionId = s.id, speedLimitKmh = s.speedLimitKmh) }
        val preview = RoutePreview()
        var last: TrackPoint? = null
        repo.forEachPoint(s.id) { p ->
            if (com.moscilez.track.data.repo.ActivityRepository.isValid(p)) {
                val tp = p.toTrackPoint()
                accumulator?.add(tp)
                preview.add(tp.lat, tp.lon)
                last = tp
            }
        }
        // Start a new segment so the gap while the process was dead is not counted as distance.
        segment = (last?.segment ?: 0) + 1
        last?.let { filter?.seed(it.copy(segment = segment)) }
        val sum = accumulator!!.summary()
        val paused = s.status == SessionStatus.PAUSED.name
        holder.update {
            it.copy(
                phase = if (paused) TrackingPhase.PAUSED else TrackingPhase.RECORDING,
                accumulatedMs = s.accumulatedActiveMs,
                resumedAt = if (paused) null else s.resumedAt ?: System.currentTimeMillis(),
                distanceM = sum.distanceM, maxSpeed = sum.maxSpeed, elevationGain = sum.elevationGain,
                altitude = sum.currentAltitude, route = preview.points(), current = preview.last(),
                acceptedPoints = sum.pointCount,
            )
        }
        if (!paused && s.resumedAt == null) persistTiming(SessionStatus.RECORDING)
        requestLocation()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (!hasLocationPermission()) {
            holder.update { it.copy(gps = GpsState.NO_PERMISSION) }
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val priority = if (fine && settings.gpsAccuracy == "HIGH") Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, settings.gpsIntervalMs.coerceIn(1000, 10_000))
            .setMinUpdateIntervalMillis(1000)
            .setWaitForAccurateLocation(false)
            .build()
        holder.update {
            it.copy(gps = when {
                !gpsEnabled() -> GpsState.DISABLED
                !fine -> GpsState.APPROXIMATE
                else -> GpsState.SEARCHING
            })
        }
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationActive = true
        } catch (e: SecurityException) {
            holder.update { it.copy(gps = GpsState.NO_PERMISSION, error = TrackingError.PERMISSION_DENIED) }
        }
    }

    private suspend fun onFix(loc: Location) {
        val s = session ?: return
        val state = holder.state.value
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gps = when {
            !fine -> GpsState.APPROXIMATE
            acc == null || acc > 25 -> GpsState.WEAK
            else -> GpsState.GOOD
        }
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
        holder.update { it.copy(gps = gps, accuracy = acc, current = LatLon(loc.latitude, loc.longitude)) }

        if (state.phase == TrackingPhase.PAUSED) return
        if (state.phase == TrackingPhase.AUTO_PAUSED) {
            if ((speed ?: 0.0) > type.stopSpeed * 2) resume()
            return
        }
        if (state.phase != TrackingPhase.RECORDING) return

        // Auto-pause for sport modes after 10 s below stop speed.
        if (settings.autoPause && !type.isVehicle) {
            val slow = (speed ?: 0.0) < type.stopSpeed
            val now = loc.time
            if (slow) {
                if (slowSince == null) slowSince = now
                if (now - slowSince!! > 10_000) {
                    slowSince = null
                    pause(auto = true)
                    return
                }
            } else slowSince = null
        }

        val now = loc.time
        val elapsed = state.accumulatedMs + (now - (state.resumedAt ?: now)).coerceAtLeast(0)
        val point = TrackPoint(
            lat = loc.latitude, lon = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else null,
            timestamp = now, elapsedMs = elapsed, speed = speed, accuracy = acc,
            bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            segment = segment,
        )
        when (val r = filter?.offer(point)) {
            is FilterResult.Accepted -> {
                accumulator?.add(point)
                buffer += ActivityPointEntity(
                    sessionId = s.id, latitude = point.lat, longitude = point.lon, altitude = point.altitude,
                    timestamp = now, elapsedMs = elapsed, speed = speed?.toFloat(), accuracy = acc?.toFloat(),
                    bearing = point.bearing?.toFloat(), verticalAccuracy = point.verticalAccuracy?.toFloat(), segment = segment,
                )
                val sum = accumulator!!.summary()
                holder.update {
                    val route = RoutePreview.append(it.route, LatLon(point.lat, point.lon))
                    it.copy(
                        distanceM = sum.distanceM, currentSpeed = speed ?: 0.0, maxSpeed = sum.maxSpeed,
                        altitude = point.altitude ?: it.altitude, elevationGain = sum.elevationGain,
                        route = route, acceptedPoints = it.acceptedPoints + 1,
                    )
                }
                if (buffer.size >= 25 || System.currentTimeMillis() - lastFlush > 15_000) flush()
            }
            is FilterResult.Rejected -> holder.update { it.copy(rejectedPoints = it.rejectedPoints + 1, currentSpeed = speed ?: it.currentSpeed) }
            null -> Unit
        }
        maybeNotify()
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return
        val batch = ArrayList(buffer)
        buffer.clear()
        lastFlush = System.currentTimeMillis()
        try {
            repo.appendPoints(batch)
        } catch (e: SQLiteFullException) {
            holder.update { it.copy(error = TrackingError.STORAGE_FULL) }
        } catch (e: Exception) {
            holder.update { it.copy(error = TrackingError.DATABASE) }
        }
    }

    private suspend fun pause(auto: Boolean) {
        val st = holder.state.value
        if (st.phase != TrackingPhase.RECORDING) return
        flush()
        val now = System.currentTimeMillis()
        val acc = st.activeMs(now)
        segment++
        holder.update { it.copy(phase = if (auto) TrackingPhase.AUTO_PAUSED else TrackingPhase.PAUSED, accumulatedMs = acc, resumedAt = null, currentSpeed = 0.0) }
        persistTiming(SessionStatus.PAUSED)
        feedback()
        updateNotification()
    }

    private suspend fun resume() {
        val st = holder.state.value
        if (st.phase != TrackingPhase.PAUSED && st.phase != TrackingPhase.AUTO_PAUSED) return
        holder.update { it.copy(phase = TrackingPhase.RECORDING, resumedAt = System.currentTimeMillis()) }
        persistTiming(SessionStatus.RECORDING)
        feedback()
        updateNotification()
    }

    private suspend fun persistTiming(status: SessionStatus) {
        val id = session?.id ?: return
        val st = holder.state.value
        val current = repo.get(id) ?: return
        repo.update(current.copy(status = status.name, accumulatedActiveMs = st.accumulatedMs, resumedAt = st.resumedAt))
    }

    private suspend fun stop() {
        val s = session
        if (s == null) {
            // Stop requested for a recording that only exists in Room (e.g. after a crash).
            val pending = repo.getUnfinished()
            if (pending != null) finalizeSession(pending.id, pending.accumulatedActiveMs + (pending.resumedAt?.let { System.currentTimeMillis() - it } ?: 0))
            holder.update { it.copy(phase = TrackingPhase.IDLE, finishedSessionId = pending?.id) }
            shutdown(); return
        }
        holder.update { it.copy(phase = TrackingPhase.FINISHING) }
        stopLocation()
        flush()
        val active = holder.state.value.activeMs(System.currentTimeMillis())
        finalizeSession(s.id, active)
        feedback()
        session = null
        holder.update { LiveTracking(phase = TrackingPhase.IDLE, finishedSessionId = s.id, type = it.type) }
        shutdown()
    }

    private suspend fun finalizeSession(id: Long, activeMs: Long) {
        val current = repo.get(id) ?: return
        repo.update(current.copy(endTime = System.currentTimeMillis()))
        val profile = container.profileRepository.getProfile()
        repo.finalize(id, activeMs, profile?.weightKg, settings.carSpeedThresholdKmh, settings.carFuelLPer100)
    }

    private fun fail(error: TrackingError) {
        holder.update { LiveTracking(phase = TrackingPhase.IDLE, error = error) }
        shutdown()
    }

    private fun stopLocation() {
        if (locationActive) fused.removeLocationUpdates(callback)
        locationActive = false
    }

    private fun shutdown() {
        stopLocation()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocation()
        // Best-effort flush; points already in Room are safe either way.
        val pending = ArrayList(buffer)
        if (pending.isNotEmpty()) {
            buffer.clear()
            CoroutineScope(Dispatchers.IO).launch { runCatching { repo.appendPoints(pending) } }
        }
        scope.cancel()
        super.onDestroy()
    }

    // --- helpers ---------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun gpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun feedback() {
        if (settings.vibration) {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            runCatching { vib.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
        }
        if (settings.sounds) {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                scope.launch { kotlinx.coroutines.delay(400); tg.release() }
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_tracking), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_tracking_desc)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun maybeNotify() {
        val now = System.currentTimeMillis()
        if (now - lastNotify > 10_000) {
            lastNotify = now
            updateNotification()
        }
    }

    private fun updateNotification() {
        if (!settings.notifications) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(holder.state.value))
        }
    }

    private fun buildNotification(state: LiveTracking): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val paused = state.phase == TrackingPhase.PAUSED || state.phase == TrackingPhase.AUTO_PAUSED
        val units = settings.units
        val text = getString(
            R.string.notif_tracking_text,
            Formatters.distance(this, state.distanceM, units),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(if (paused) R.string.notif_tracking_paused else R.string.notif_tracking_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!paused && state.resumedAt != null) {
            builder.setUsesChronometer(true).setWhen(now - state.activeMs(now)).setShowWhen(true)
        }
        // Vehicles: no in-notification controls, to avoid interacting with the phone while driving.
        if (!state.type.isVehicle && state.sessionId != null) {
            val toggle = if (paused) ACTION_RESUME else ACTION_PAUSE
            builder.addAction(0, getString(if (paused) R.string.action_resume else R.string.action_pause), servicePending(toggle, 1))
        }
        return builder.build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, TrackingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.moscilez.track.START"
        const val ACTION_PAUSE = "com.moscilez.track.PAUSE"
        const val ACTION_RESUME = "com.moscilez.track.RESUME"
        const val ACTION_STOP = "com.moscilez.track.STOP"
        const val ACTION_RESTORE = "com.moscilez.track.RESTORE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TRIP = "trip"
        const val EXTRA_LIMIT = "limit"

        fun command(context: Context, action: String, build: Intent.() -> Unit = {}) {
            val intent = Intent(context, TrackingService::class.java).setAction(action).apply(build)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Keeps a bounded, decimated copy of the route for live drawing. */
class RoutePreview {
    private var list = ArrayList<LatLon>()
    fun add(lat: Double, lon: Double) { list = ArrayList(append(list, LatLon(lat, lon))) }
    fun points(): List<LatLon> = list
    fun last(): LatLon? = list.lastOrNull()

    companion object {
        private const val MAX = 2500

        fun append(route: List<LatLon>, p: LatLon): List<LatLon> {
            val last = route.lastOrNull()
            if (last != null && TrackMath.distance(last.lat, last.lon, p.lat, p.lon) < 4.0) return route
            val next = route + p
            return if (next.size > MAX) next.filterIndexed { i, _ -> i % 2 == 0 || i == next.lastIndex } else next
        }
    }
}
