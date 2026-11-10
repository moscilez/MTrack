package com.moscilez.track.domain.model

/** Lightweight summary of a finished activity used by statistics, goals and recommendations. */
data class ActivityStat(
    val id: Long,
    val type: ActivityType,
    val startTime: Long,
    val distanceM: Double,
    val durationMs: Long,
    val movingMs: Long = durationMs,
    val stoppedMs: Long = 0,
    val avgSpeed: Double,
    val maxSpeed: Double = 0.0,
    val elevationGain: Double = 0.0,
    val bestPaceSecPerKm: Double? = null,
    val stopsCount: Int = 0,
    val overSpeedMs: Long = 0,
    val fuelLiters: Double? = null,
    val calories: Double? = null,
)

enum class GoalType { PERIOD_DISTANCE, ACTIVE_TIME, WORKOUT_COUNT, ACTIVE_DAYS, TYPE_DISTANCE, TRIP_COUNT, CUSTOM }

enum class GoalPeriod { WEEK, MONTH, DEADLINE }

data class GoalSpec(
    val id: Long,
    val type: GoalType,
    val title: String,
    /** Base units: metres for distance, milliseconds for time, plain count otherwise. */
    val target: Double,
    val activityType: ActivityType?,
    val period: GoalPeriod,
    val startDate: Long,
    val deadline: Long?,
    val manualProgress: Double,
)

data class GoalStatus(
    val goal: GoalSpec,
    val value: Double,
    val periodStart: Long,
    val periodEnd: Long,
    val fraction: Double,
    val achieved: Boolean,
    val daysLeft: Int,
    /** Share of the period already elapsed; compared with [fraction] to judge pace. */
    val timeFraction: Double,
)
