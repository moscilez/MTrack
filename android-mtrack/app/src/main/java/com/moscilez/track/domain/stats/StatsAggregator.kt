package com.moscilez.track.domain.stats

import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.ActivityType
import java.time.DayOfWeek
import java.time.ZoneId

data class PeriodTotals(
    val count: Int,
    val distanceM: Double,
    val durationMs: Long,
    val movingMs: Long,
    val stoppedMs: Long,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val elevationGain: Double,
    val avgPaceSecPerKm: Double?,
    val calories: Double,
) {
    companion object {
        val EMPTY = PeriodTotals(0, 0.0, 0, 0, 0, 0.0, 0.0, 0.0, null, 0.0)
    }
}

data class TypeShare(val type: ActivityType, val count: Int, val distanceM: Double, val durationMs: Long)

data class VehicleTotals(val trips: Int, val distanceM: Double, val drivingMs: Long, val stoppedMs: Long, val stops: Int, val overSpeedMs: Long, val maxSpeed: Double, val fuelLiters: Double?)

data class Bucket(val start: Long, val distanceM: Double, val durationMs: Long)

data class StatsReport(
    val totals: PeriodTotals,
    val previous: PeriodTotals?,
    val byType: List<TypeShare>,
    val byWeekday: Map<DayOfWeek, Double>,
    val vehicle: VehicleTotals,
    val trend: List<Bucket>,
    val streakDays: Int,
)

/** Pure aggregation of activity summaries for a period; runs off the main thread. */
object StatsAggregator {
    fun totals(items: List<ActivityStat>): PeriodTotals {
        if (items.isEmpty()) return PeriodTotals.EMPTY
        val dist = items.sumOf { it.distanceM }
        val dur = items.sumOf { it.durationMs }
        val moving = items.sumOf { it.movingMs }
        val paced = items.filter { it.type.showsPace }
        val pacedMoving = paced.sumOf { it.movingMs }
        val pacedDist = paced.sumOf { it.distanceM }
        return PeriodTotals(
            count = items.size,
            distanceM = dist,
            durationMs = dur,
            movingMs = moving,
            stoppedMs = items.sumOf { it.stoppedMs },
            avgSpeed = if (dur > 0) dist / (dur / 1000.0) else 0.0,
            maxSpeed = items.maxOf { it.maxSpeed },
            elevationGain = items.sumOf { it.elevationGain },
            avgPaceSecPerKm = if (pacedDist >= 100) (pacedMoving / 1000.0) / (pacedDist / 1000.0) else null,
            calories = items.sumOf { it.calories ?: 0.0 },
        )
    }

    fun report(all: List<ActivityStat>, period: StatsPeriod, now: Long, zone: ZoneId): StatsReport {
        val range = Periods.range(period, now, zone)
        val current = all.filter { it.startTime in range }
        val previous = if (period == StatsPeriod.ALL) null else {
            val prev = Periods.range(period, now, zone, -1)
            totals(all.filter { it.startTime in prev })
        }
        val byType = current.groupBy { it.type }.map { (t, l) ->
            TypeShare(t, l.size, l.sumOf { it.distanceM }, l.sumOf { it.durationMs })
        }.sortedByDescending { it.durationMs }
        val byWeekday = DayOfWeek.entries.associateWith { d ->
            current.filter { Periods.dayOf(it.startTime, zone).dayOfWeek == d }.sumOf { it.durationMs }.toDouble()
        }
        val veh = current.filter { it.type.isVehicle }
        val fuel = veh.mapNotNull { it.fuelLiters }
        val vehicle = VehicleTotals(
            trips = veh.size,
            distanceM = veh.sumOf { it.distanceM },
            drivingMs = veh.sumOf { it.movingMs },
            stoppedMs = veh.sumOf { it.stoppedMs },
            stops = veh.sumOf { it.stopsCount },
            overSpeedMs = veh.sumOf { it.overSpeedMs },
            maxSpeed = veh.maxOfOrNull { it.maxSpeed } ?: 0.0,
            fuelLiters = if (fuel.isEmpty()) null else fuel.sum(),
        )
        return StatsReport(
            totals = totals(current),
            previous = previous,
            byType = byType,
            byWeekday = byWeekday,
            vehicle = vehicle,
            trend = trend(all, period, now, zone),
            streakDays = streak(all, now, zone),
        )
    }

    /** Buckets for the trend chart: hours→ days for week/month, months for year/all. */
    fun trend(all: List<ActivityStat>, period: StatsPeriod, now: Long, zone: ZoneId): List<Bucket> {
        val (unit, n) = when (period) {
            StatsPeriod.DAY, StatsPeriod.WEEK -> StatsPeriod.DAY to 7
            StatsPeriod.MONTH -> StatsPeriod.WEEK to 8
            StatsPeriod.YEAR, StatsPeriod.ALL -> StatsPeriod.MONTH to 12
        }
        return (n - 1 downTo 0).map { back ->
            val r = Periods.range(unit, now, zone, -back)
            val items = all.filter { it.startTime in r }
            Bucket(r.start, items.sumOf { it.distanceM }, items.sumOf { it.durationMs })
        }
    }

    /** Consecutive days with at least one non-vehicle activity, ending today or yesterday. */
    fun streak(all: List<ActivityStat>, now: Long, zone: ZoneId): Int {
        val days = all.filter { !it.type.isVehicle }.map { Periods.dayOf(it.startTime, zone) }.toHashSet()
        var day = Periods.dayOf(now, zone)
        if (day !in days) day = day.minusDays(1)
        var count = 0
        while (day in days) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
