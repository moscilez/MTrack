package com.moscilez.track.domain.model

/**
 * Recording modes. Limits are used by the GPS filter (implausible speed rejection),
 * stop detection and calorie estimation.
 */
enum class ActivityType(
    val isSport: Boolean,
    val isVehicle: Boolean,
    /** Highest physically plausible speed for the mode, m/s. */
    val maxPlausibleSpeed: Double,
    /** Below this speed the user is considered stopped, m/s. */
    val stopSpeed: Double,
    /** Minimum stationary duration to register a stop, ms. */
    val minStopMs: Long,
    val showsPace: Boolean,
) {
    RUN(true, false, 12.5, 0.6, 20_000, true),
    WALK(true, false, 6.0, 0.35, 30_000, true),
    BIKE(true, false, 30.0, 1.0, 20_000, false),
    SCOOTER(false, false, 22.0, 0.8, 20_000, false),
    HIKE(true, false, 6.0, 0.3, 45_000, true),
    CAR(false, true, 75.0, 1.2, 10_000, false),
    MOTO(false, true, 85.0, 1.2, 10_000, false),
    OTHER(false, false, 85.0, 0.5, 30_000, false);

    companion object {
        fun from(value: String?): ActivityType = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class TripType { PERSONAL, WORK, TRAVEL }

enum class SessionStatus { RECORDING, PAUSED, FINISHED }

enum class SegmentKind { STOP, PAUSE, SPLIT }

enum class PeakKind { MAX_SPEED, MAX_ALTITUDE, MIN_ALTITUDE, MAX_CLIMB, MAX_DESCENT, MAX_ACCEL, MAX_DECEL }

enum class Units { METRIC, IMPERIAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class GpsAccuracyMode { HIGH, BALANCED }
