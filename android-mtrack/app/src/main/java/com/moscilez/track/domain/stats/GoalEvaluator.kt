package com.moscilez.track.domain.stats

import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalSpec
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.domain.model.GoalType
import java.time.ZoneId
import kotlin.math.ceil

object GoalEvaluator {
    fun currentRange(goal: GoalSpec, now: Long, zone: ZoneId): TimeRange = when (goal.period) {
        GoalPeriod.WEEK -> Periods.range(StatsPeriod.WEEK, now, zone)
        GoalPeriod.MONTH -> Periods.range(StatsPeriod.MONTH, now, zone)
        GoalPeriod.DEADLINE -> TimeRange(goal.startDate, goal.deadline ?: Long.MAX_VALUE)
    }

    fun evaluate(goal: GoalSpec, stats: List<ActivityStat>, now: Long, zone: ZoneId, range: TimeRange = currentRange(goal, now, zone)): GoalStatus {
        val inRange = stats.filter { it.startTime in range }
        val value = when (goal.type) {
            GoalType.PERIOD_DISTANCE -> inRange.filter { !it.type.isVehicle }.sumOf { it.distanceM }
            GoalType.ACTIVE_TIME -> inRange.filter { !it.type.isVehicle }.sumOf { it.durationMs }.toDouble()
            GoalType.WORKOUT_COUNT -> inRange.count { it.type.isSport }.toDouble()
            GoalType.ACTIVE_DAYS -> inRange.filter { !it.type.isVehicle }.map { Periods.dayOf(it.startTime, zone) }.distinct().size.toDouble()
            GoalType.TYPE_DISTANCE -> inRange.filter { it.type == goal.activityType }.sumOf { it.distanceM }
            GoalType.TRIP_COUNT -> inRange.count { it.type.isVehicle }.toDouble()
            GoalType.CUSTOM -> goal.manualProgress
        }
        val fraction = if (goal.target <= 0) 0.0 else (value / goal.target)
        val end = range.end.coerceAtMost(Long.MAX_VALUE)
        val total = (end - range.start).coerceAtLeast(1)
        val timeFraction = if (end == Long.MAX_VALUE) 0.0 else ((now - range.start).toDouble() / total).coerceIn(0.0, 1.0)
        val daysLeft = if (end == Long.MAX_VALUE) -1 else ceil((end - now).coerceAtLeast(0) / 86_400_000.0).toInt()
        return GoalStatus(goal, value, range.start, end, fraction, fraction >= 1.0, daysLeft, timeFraction)
    }
}
