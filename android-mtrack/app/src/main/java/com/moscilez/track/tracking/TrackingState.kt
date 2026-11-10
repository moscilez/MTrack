package com.moscilez.track.tracking

import com.moscilez.track.domain.model.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class TrackingPhase { IDLE, STARTING, RECORDING, PAUSED, AUTO_PAUSED, FINISHING }

enum class GpsState { NO_PERMISSION, DISABLED, SEARCHING, WEAK, GOOD, APPROXIMATE }

enum class TrackingError { PERMISSION_DENIED, GPS_DISABLED, START_FAILED, STORAGE_FULL, DATABASE }

data class LatLon(val lat: Double, val lon: Double)

data class LiveTracking(
    val phase: TrackingPhase = TrackingPhase.IDLE,
    val sessionId: Long? = null,
    val type: ActivityType = ActivityType.RUN,
    val accumulatedMs: Long = 0,
    /** Wall-clock start of the running interval; null while paused. */
    val resumedAt: Long? = null,
    val distanceM: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val altitude: Double? = null,
    val elevationGain: Double = 0.0,
    val gps: GpsState = GpsState.SEARCHING,
    val accuracy: Double? = null,
    val satellites: Int? = null,
    /** Decimated preview of the route for the live map; the full route lives in Room. */
    val route: List<LatLon> = emptyList(),
    val current: LatLon? = null,
    val acceptedPoints: Int = 0,
    val rejectedPoints: Int = 0,
    val speedLimitKmh: Double? = null,
    val finishedSessionId: Long? = null,
    val error: TrackingError? = null,
) {
    fun activeMs(now: Long): Long = accumulatedMs + (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun avgSpeed(now: Long): Double {
        val ms = activeMs(now)
        return if (ms <= 0) 0.0 else distanceM / (ms / 1000.0)
    }

    val isActive: Boolean
        get() = phase == TrackingPhase.RECORDING || phase == TrackingPhase.PAUSED ||
            phase == TrackingPhase.AUTO_PAUSED || phase == TrackingPhase.STARTING
}

/** Process-wide live tracking state, written only by [TrackingService]. */
class TrackingStateHolder {
    private val _state = MutableStateFlow(LiveTracking())
    val state: StateFlow<LiveTracking> = _state

    fun update(transform: (LiveTracking) -> LiveTracking) = _state.update(transform)

    fun consumeFinished() = _state.update { it.copy(finishedSessionId = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}
