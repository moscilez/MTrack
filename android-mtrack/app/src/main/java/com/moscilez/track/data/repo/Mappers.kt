package com.moscilez.track.data.repo

import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalSpec
import com.moscilez.track.domain.model.GoalType

fun ActivitySessionEntity.toStat() = ActivityStat(
    id = id,
    type = ActivityType.from(type),
    startTime = startTime,
    distanceM = distanceM,
    durationMs = durationMs,
    movingMs = movingMs,
    stoppedMs = stoppedMs,
    avgSpeed = avgSpeed,
    maxSpeed = maxSpeed,
    elevationGain = elevationGain,
    bestPaceSecPerKm = bestPaceSecPerKm,
    stopsCount = stopsCount,
    overSpeedMs = overSpeedMs,
    fuelLiters = fuelLiters,
    calories = calories,
)

fun GoalEntity.toSpec() = GoalSpec(
    id = id,
    type = runCatching { GoalType.valueOf(type) }.getOrDefault(GoalType.CUSTOM),
    title = title,
    target = target,
    activityType = activityType?.let { ActivityType.from(it) },
    period = runCatching { GoalPeriod.valueOf(period) }.getOrDefault(GoalPeriod.WEEK),
    startDate = startDate,
    deadline = deadline,
    manualProgress = manualProgress,
)
