package com.moscilez.track.domain.recommend

import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.domain.stats.Periods
import com.moscilez.track.domain.stats.PersonalRecords
import com.moscilez.track.domain.stats.RecordKind
import com.moscilez.track.domain.stats.StatsPeriod
import java.time.ZoneId
import kotlin.math.roundToInt

enum class RecKind {
    WEEK_DISTANCE_UP, WEEK_DISTANCE_DOWN, SPEED_UP, SPEED_DOWN, LOAD_SPIKE, RECOVERY_DAY,
    REGULARITY, MOST_FREQUENT, GOAL_BEHIND, GOAL_DONE, LONG_BREAK, NEW_RECORD, ROUTE_FASTER, ROUTE_SLOWER,
}

enum class Severity { POSITIVE, INFO, ATTENTION }

/**
 * A locally derived, explainable observation. Text is resolved by the UI from [kind] and the
 * typed arguments, so both languages are supported without the engine knowing about resources.
 */
data class Recommendation(
    val kind: RecKind,
    val severity: Severity,
    val createdAt: Long,
    val percent: Int? = null,
    val type: ActivityType? = null,
    val days: Int? = null,
    val count: Int? = null,
    val goalTitle: String? = null,
    val goalType: GoalType? = null,
    val remaining: Double? = null,
    val record: RecordKind? = null,
)

/**
 * Rule-based engine. Runs fully offline on summaries; no external AI or network. Each rule
 * has a clear numeric basis so the UI can explain it. Never phrased as medical advice.
 */
object RecommendationEngine {
    private const val DAY = 86_400_000L

    fun generate(
        stats: List<ActivityStat>,
        goals: List<GoalStatus>,
        now: Long,
        zone: ZoneId,
        muted: Set<RecKind> = emptySet(),
    ): List<Recommendation> {
        val out = mutableListOf<Recommendation>()
        val sport = stats.filter { !it.type.isVehicle }.sortedBy { it.startTime }
        weekComparison(sport, now, zone)?.let(out::add)
        loadSpike(sport, now, zone)?.let(out::add)
        recovery(sport, now, zone)?.let(out::add)
        out += speedTrends(stats, now)
        longBreak(sport, now)?.let(out::add)
        regularity(sport, now, zone)?.let(out::add)
        mostFrequent(stats, now)?.let(out::add)
        out += goalRules(goals, now)
        newRecord(stats, now)?.let(out::add)
        routeComparison(stats, now)?.let(out::add)
        return out.filter { it.kind !in muted }
            .sortedWith(compareBy<Recommendation> { it.severity.ordinal.let { o -> if (o == 2) 0 else o + 1 } })
    }

    internal fun pct(cur: Double, prev: Double): Int = ((cur - prev) / prev * 100).roundToInt()

    fun weekComparison(sport: List<ActivityStat>, now: Long, zone: ZoneId): Recommendation? {
        val cur = Periods.range(StatsPeriod.WEEK, now, zone)
        val prev = Periods.range(StatsPeriod.WEEK, now, zone, -1)
        val c = sport.filter { it.startTime in cur }.sumOf { it.distanceM }
        val p = sport.filter { it.startTime in prev }.sumOf { it.distanceM }
        if (p < 500 || c <= 0) return null
        val change = pct(c, p)
        return when {
            change >= 5 -> Recommendation(RecKind.WEEK_DISTANCE_UP, Severity.POSITIVE, now, percent = change)
            change <= -5 -> Recommendation(RecKind.WEEK_DISTANCE_DOWN, Severity.INFO, now, percent = -change)
            else -> null
        }
    }

    /** Current week distance more than 50% above the 4-week average signals a steep jump. */
    fun loadSpike(sport: List<ActivityStat>, now: Long, zone: ZoneId): Recommendation? {
        val cur = Periods.range(StatsPeriod.WEEK, now, zone)
        val c = sport.filter { it.startTime in cur }.sumOf { it.distanceM }
        val prior = (1..4).map { back ->
            val r = Periods.range(StatsPeriod.WEEK, now, zone, -back)
            sport.filter { it.startTime in r }.sumOf { it.distanceM }
        }
        val avg = prior.average()
        if (prior.count { it > 0 } < 3 || avg < 2000) return null
        val change = pct(c, avg)
        return if (change >= 50) Recommendation(RecKind.LOAD_SPIKE, Severity.ATTENTION, now, percent = change) else null
    }

    /**
     * Suggests an easy day after 5+ consecutive active days, or when the last three
     * activities of one type were each >10% faster than that type's typical pace.
     */
    fun recovery(sport: List<ActivityStat>, now: Long, zone: ZoneId): Recommendation? {
        val days = sport.map { Periods.dayOf(it.startTime, zone) }.toHashSet()
        var day = Periods.dayOf(now, zone)
        var streak = 0
        while (day in days) { streak++; day = day.minusDays(1) }
        if (streak >= 5) return Recommendation(RecKind.RECOVERY_DAY, Severity.ATTENTION, now, days = streak)
        val last3 = sport.takeLast(3)
        if (last3.size == 3 && last3.all { it.type == last3[0].type } && now - last3[0].startTime < 5 * DAY) {
            val baseline = sport.dropLast(3).filter { it.type == last3[0].type && it.distanceM > 500 }
            if (baseline.size >= 3) {
                val avg = baseline.map { it.avgSpeed }.average()
                if (last3.all { it.avgSpeed > avg * 1.1 }) {
                    return Recommendation(RecKind.RECOVERY_DAY, Severity.ATTENTION, now, count = 3, type = last3[0].type)
                }
            }
        }
        return null
    }

    /** Compares the last 3 activities of each type with the 5 before them. */
    fun speedTrends(stats: List<ActivityStat>, now: Long): List<Recommendation> =
        stats.filter { !it.type.isVehicle && it.distanceM >= 500 }.groupBy { it.type }.mapNotNull { (type, list) ->
            val sorted = list.sortedBy { it.startTime }
            if (sorted.size < 6) return@mapNotNull null
            val recent = sorted.takeLast(3).map { it.avgSpeed }.average()
            val before = sorted.dropLast(3).takeLast(5).map { it.avgSpeed }.average()
            if (before <= 0) return@mapNotNull null
            val change = pct(recent, before)
            when {
                change >= 5 -> Recommendation(RecKind.SPEED_UP, Severity.POSITIVE, now, percent = change, type = type)
                change <= -8 -> Recommendation(RecKind.SPEED_DOWN, Severity.INFO, now, percent = -change, type = type)
                else -> null
            }
        }

    fun longBreak(sport: List<ActivityStat>, now: Long): Recommendation? {
        val last = sport.maxOfOrNull { it.startTime } ?: return null
        val days = ((now - last) / DAY).toInt()
        return if (days >= 7) Recommendation(RecKind.LONG_BREAK, Severity.INFO, now, days = days) else null
    }

    fun regularity(sport: List<ActivityStat>, now: Long, zone: ZoneId): Recommendation? {
        val from = now - 14 * DAY
        val active = sport.filter { it.startTime >= from }.map { Periods.dayOf(it.startTime, zone) }.distinct().size
        return if (active >= 6) Recommendation(RecKind.REGULARITY, Severity.POSITIVE, now, days = active) else null
    }

    fun mostFrequent(stats: List<ActivityStat>, now: Long): Recommendation? {
        val recent = stats.filter { it.startTime >= now - 30 * DAY }
        if (recent.size < 4) return null
        val (type, list) = recent.groupBy { it.type }.maxByOrNull { it.value.size } ?: return null
        return Recommendation(RecKind.MOST_FREQUENT, Severity.INFO, now, type = type, count = list.size)
    }

    fun goalRules(goals: List<GoalStatus>, now: Long): List<Recommendation> = goals.mapNotNull { g ->
        when {
            g.achieved -> Recommendation(RecKind.GOAL_DONE, Severity.POSITIVE, now, goalTitle = g.goal.title, goalType = g.goal.type)
            g.daysLeft > 0 && g.timeFraction >= 0.4 && g.fraction < g.timeFraction - 0.15 ->
                Recommendation(
                    RecKind.GOAL_BEHIND, Severity.ATTENTION, now, goalTitle = g.goal.title, goalType = g.goal.type,
                    remaining = (g.goal.target - g.value).coerceAtLeast(0.0), days = g.daysLeft,
                )
            else -> null
        }
    }

    /** Flags a record set within the last 3 days. */
    fun newRecord(stats: List<ActivityStat>, now: Long): Recommendation? {
        val recentIds = stats.filter { it.startTime >= now - 3 * DAY }.map { it.id }.toSet()
        if (recentIds.isEmpty()) return null
        val byType = stats.groupBy { it.type }
        val rec = PersonalRecords.compute(stats).firstOrNull { r ->
            r.activityId in recentIds && (byType[r.type]?.size ?: 0) >= 3 &&
                r.kind in setOf(RecordKind.LONGEST_DISTANCE, RecordKind.FASTEST_AVG_SPEED, RecordKind.BEST_PACE)
        } ?: return null
        return Recommendation(RecKind.NEW_RECORD, Severity.POSITIVE, now, type = rec.type, record = rec.kind)
    }

    /** Compares the latest activity with earlier ones of the same type and similar distance (±10%). */
    fun routeComparison(stats: List<ActivityStat>, now: Long): Recommendation? {
        val last = stats.filter { !it.type.isVehicle }.maxByOrNull { it.startTime } ?: return null
        if (last.distanceM < 1000 || now - last.startTime > 3 * DAY) return null
        val similar = stats.filter {
            it.id != last.id && it.type == last.type && it.distanceM in (last.distanceM * 0.9)..(last.distanceM * 1.1)
        }
        if (similar.size < 2) return null
        val avg = similar.map { it.avgSpeed }.average()
        if (avg <= 0) return null
        val change = pct(last.avgSpeed, avg)
        return when {
            change >= 3 -> Recommendation(RecKind.ROUTE_FASTER, Severity.POSITIVE, now, percent = change, type = last.type, count = similar.size)
            change <= -3 -> Recommendation(RecKind.ROUTE_SLOWER, Severity.INFO, now, percent = -change, type = last.type, count = similar.size)
            else -> null
        }
    }
}
