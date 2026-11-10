package com.moscilez.track.domain

import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalSpec
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.domain.stats.GoalEvaluator
import com.moscilez.track.domain.stats.Periods
import com.moscilez.track.domain.stats.PersonalRecords
import com.moscilez.track.domain.stats.RecordKind
import com.moscilez.track.domain.stats.StatsAggregator
import com.moscilez.track.domain.stats.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StatsAndGoalsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    // Wednesday 2026-11-04 12:00 local.
    private val now = LocalDateTime.of(2026, 11, 4, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = 86_400_000L

    private fun stat(id: Long, type: ActivityType, start: Long, km: Double, minutes: Long, maxSpeed: Double = 0.0, pace: Double? = null) =
        ActivityStat(
            id = id, type = type, startTime = start, distanceM = km * 1000, durationMs = minutes * 60_000,
            avgSpeed = km * 1000 / (minutes * 60.0), maxSpeed = maxSpeed, bestPaceSecPerKm = pace,
        )

    @Test
    fun weekRangeStartsOnMonday() {
        val r = Periods.range(StatsPeriod.WEEK, now, zone)
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0).atZone(zone).toInstant().toEpochMilli(), r.start)
        assertEquals(r.start + 7 * day, r.end)
        assertTrue(now in r)
    }

    @Test
    fun totalsSumDistanceAndTime() {
        val t = StatsAggregator.totals(listOf(stat(1, ActivityType.RUN, now, 5.0, 30), stat(2, ActivityType.WALK, now, 3.0, 40)))
        assertEquals(2, t.count)
        assertEquals(8000.0, t.distanceM, 1e-6)
        assertEquals(70 * 60_000L, t.durationMs)
    }

    @Test
    fun streakCountsConsecutiveDaysIgnoringVehicles() {
        val all = listOf(
            stat(1, ActivityType.RUN, now, 5.0, 30),
            stat(2, ActivityType.WALK, now - day, 2.0, 20),
            stat(3, ActivityType.RUN, now - 2 * day, 4.0, 25),
            stat(4, ActivityType.CAR, now - 3 * day, 40.0, 50),
        )
        assertEquals(3, StatsAggregator.streak(all, now, zone))
    }

    @Test
    fun streakStillCountsWhenTodayIsEmpty() {
        val all = listOf(stat(1, ActivityType.RUN, now - day, 5.0, 30))
        assertEquals(1, StatsAggregator.streak(all, now, zone))
    }

    @Test
    fun weeklyDistanceGoalExcludesVehiclesAndOldActivities() {
        val goal = GoalSpec(1, GoalType.PERIOD_DISTANCE, "60 km", 60_000.0, null, GoalPeriod.WEEK, 0, null, 0.0)
        val stats = listOf(
            stat(1, ActivityType.RUN, now, 30.0, 180),
            stat(2, ActivityType.CAR, now, 100.0, 90),
            stat(3, ActivityType.BIKE, now - 10 * day, 50.0, 120),
        )
        val s = GoalEvaluator.evaluate(goal, stats, now, zone)
        assertEquals(30_000.0, s.value, 1e-6)
        assertEquals(0.5, s.fraction, 1e-9)
        assertFalse(s.achieved)
        assertEquals(5, s.daysLeft)
    }

    @Test
    fun customGoalUsesManualProgress() {
        val goal = GoalSpec(2, GoalType.CUSTOM, "Push-ups", 100.0, null, GoalPeriod.DEADLINE, now - day, now + day, 120.0)
        val s = GoalEvaluator.evaluate(goal, emptyList(), now, zone)
        assertTrue(s.achieved)
    }

    @Test
    fun speedRecordsNeedAtLeastOneKilometre() {
        val all = listOf(
            stat(1, ActivityType.RUN, now, 0.3, 1),
            stat(2, ActivityType.RUN, now - day, 10.0, 50, maxSpeed = 5.0, pace = 280.0),
        )
        val recs = PersonalRecords.compute(all)
        val fastest = recs.first { it.kind == RecordKind.FASTEST_AVG_SPEED }
        assertEquals(2L, fastest.activityId)
        assertEquals(280.0, recs.first { it.kind == RecordKind.BEST_PACE }.value, 1e-9)
        assertTrue(PersonalRecords.recordsSetBy(2, all).any { it.kind == RecordKind.LONGEST_DISTANCE })
    }
}
