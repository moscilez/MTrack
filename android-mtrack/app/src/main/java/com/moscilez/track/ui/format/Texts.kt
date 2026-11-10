package com.moscilez.track.ui.format

import android.content.Context
import com.moscilez.track.R
import com.moscilez.track.domain.model.GoalPeriod
import com.moscilez.track.domain.model.GoalType
import com.moscilez.track.domain.recommend.RecKind
import com.moscilez.track.domain.recommend.Recommendation
import com.moscilez.track.domain.stats.RecordKind
import com.moscilez.track.ui.components.label

/** Resolves typed domain values (recommendations, goals, records) into localized text. */
object Texts {
    fun goalTypeLabel(t: GoalType): Int = when (t) {
        GoalType.PERIOD_DISTANCE -> R.string.goal_type_distance
        GoalType.ACTIVE_TIME -> R.string.goal_type_time
        GoalType.WORKOUT_COUNT -> R.string.goal_type_workouts
        GoalType.ACTIVE_DAYS -> R.string.goal_type_days
        GoalType.TYPE_DISTANCE -> R.string.goal_type_type_distance
        GoalType.TRIP_COUNT -> R.string.goal_type_trips
        GoalType.CUSTOM -> R.string.goal_type_custom
    }

    fun goalPeriodLabel(p: GoalPeriod): Int = when (p) {
        GoalPeriod.WEEK -> R.string.goal_period_week
        GoalPeriod.MONTH -> R.string.goal_period_month
        GoalPeriod.DEADLINE -> R.string.goal_period_deadline
    }

    /** Goal values are stored in base units: metres, milliseconds or plain counts. */
    fun goalValue(context: Context, type: GoalType, value: Double, units: String): String = when (type) {
        GoalType.PERIOD_DISTANCE, GoalType.TYPE_DISTANCE -> Formatters.distance(context, value, units)
        GoalType.ACTIVE_TIME -> Formatters.duration(context, value.toLong())
        GoalType.CUSTOM -> Formatters.number(context, value, if (value % 1.0 == 0.0) 0 else 1)
        else -> value.toInt().toString()
    }

    fun recordLabel(kind: RecordKind): Int = when (kind) {
        RecordKind.LONGEST_DISTANCE -> R.string.record_longest_distance
        RecordKind.LONGEST_DURATION -> R.string.record_longest_duration
        RecordKind.FASTEST_AVG_SPEED -> R.string.record_fastest_avg
        RecordKind.TOP_SPEED -> R.string.record_top_speed
        RecordKind.BEST_PACE -> R.string.record_best_pace
        RecordKind.MOST_CLIMB -> R.string.record_most_climb
    }

    fun recordValue(context: Context, kind: RecordKind, value: Double, units: String): String = when (kind) {
        RecordKind.LONGEST_DISTANCE -> Formatters.distance(context, value, units)
        RecordKind.LONGEST_DURATION -> Formatters.duration(context, value.toLong())
        RecordKind.FASTEST_AVG_SPEED, RecordKind.TOP_SPEED -> Formatters.speed(context, value, units)
        RecordKind.BEST_PACE -> Formatters.pace(context, value, units)
        RecordKind.MOST_CLIMB -> Formatters.altitude(context, value, units)
    }

    fun recommendation(context: Context, r: Recommendation, units: String): String {
        val type = r.type?.let { context.getString(it.label()) }.orEmpty()
        return when (r.kind) {
            RecKind.WEEK_DISTANCE_UP -> context.getString(R.string.rec_week_up, r.percent ?: 0)
            RecKind.WEEK_DISTANCE_DOWN -> context.getString(R.string.rec_week_down, r.percent ?: 0)
            RecKind.SPEED_UP -> context.getString(R.string.rec_speed_up, type, r.percent ?: 0)
            RecKind.SPEED_DOWN -> context.getString(R.string.rec_speed_down, type, r.percent ?: 0)
            RecKind.LOAD_SPIKE -> context.getString(R.string.rec_load_spike, r.percent ?: 0)
            RecKind.RECOVERY_DAY -> if (r.days != null) context.resources.getQuantityString(R.plurals.rec_recovery_days, r.days, r.days)
            else context.getString(R.string.rec_recovery_fast, type)
            RecKind.REGULARITY -> context.getString(R.string.rec_regularity, r.days ?: 0)
            RecKind.MOST_FREQUENT -> context.getString(R.string.rec_most_frequent, type, r.count ?: 0)
            RecKind.GOAL_BEHIND -> context.resources.getQuantityString(
                R.plurals.rec_goal_behind, r.days ?: 0, r.goalTitle.orEmpty(),
                goalValue(context, r.goalType ?: GoalType.CUSTOM, r.remaining ?: 0.0, units), r.days ?: 0,
            )
            RecKind.GOAL_DONE -> context.getString(R.string.rec_goal_done, r.goalTitle.orEmpty())
            RecKind.LONG_BREAK -> context.resources.getQuantityString(R.plurals.rec_long_break, r.days ?: 0, r.days ?: 0)
            RecKind.NEW_RECORD -> context.getString(R.string.rec_new_record, type, r.record?.let { context.getString(recordLabel(it)) }.orEmpty())
            RecKind.ROUTE_FASTER -> context.getString(R.string.rec_route_faster, type, r.percent ?: 0)
            RecKind.ROUTE_SLOWER -> context.getString(R.string.rec_route_slower, type, r.percent ?: 0)
        }
    }

    /** One-line explanation of how the observation was computed. */
    fun recommendationWhy(kind: RecKind): Int = when (kind) {
        RecKind.WEEK_DISTANCE_UP, RecKind.WEEK_DISTANCE_DOWN -> R.string.why_week
        RecKind.SPEED_UP, RecKind.SPEED_DOWN -> R.string.why_speed
        RecKind.LOAD_SPIKE -> R.string.why_load
        RecKind.RECOVERY_DAY -> R.string.why_recovery
        RecKind.REGULARITY -> R.string.why_regularity
        RecKind.MOST_FREQUENT -> R.string.why_frequent
        RecKind.GOAL_BEHIND, RecKind.GOAL_DONE -> R.string.why_goal
        RecKind.LONG_BREAK -> R.string.why_break
        RecKind.NEW_RECORD -> R.string.why_record
        RecKind.ROUTE_FASTER, RecKind.ROUTE_SLOWER -> R.string.why_route
    }
}
