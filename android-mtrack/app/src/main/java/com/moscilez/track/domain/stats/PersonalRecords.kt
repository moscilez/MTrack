package com.moscilez.track.domain.stats

import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.ActivityType

enum class RecordKind { LONGEST_DISTANCE, LONGEST_DURATION, FASTEST_AVG_SPEED, TOP_SPEED, BEST_PACE, MOST_CLIMB }

data class PersonalRecord(val type: ActivityType, val kind: RecordKind, val value: Double, val activityId: Long, val time: Long)

object PersonalRecords {
    /** Minimum distance for speed/pace records so short GPS blips cannot set records. */
    const val MIN_DISTANCE_FOR_SPEED = 1000.0

    fun compute(all: List<ActivityStat>): List<PersonalRecord> = all.groupBy { it.type }.flatMap { (type, list) ->
        val out = mutableListOf<PersonalRecord>()
        list.maxByOrNull { it.distanceM }?.takeIf { it.distanceM > 0 }?.let {
            out += PersonalRecord(type, RecordKind.LONGEST_DISTANCE, it.distanceM, it.id, it.startTime)
        }
        list.maxByOrNull { it.durationMs }?.takeIf { it.durationMs > 0 }?.let {
            out += PersonalRecord(type, RecordKind.LONGEST_DURATION, it.durationMs.toDouble(), it.id, it.startTime)
        }
        val eligible = list.filter { it.distanceM >= MIN_DISTANCE_FOR_SPEED }
        eligible.maxByOrNull { it.avgSpeed }?.let {
            out += PersonalRecord(type, RecordKind.FASTEST_AVG_SPEED, it.avgSpeed, it.id, it.startTime)
        }
        list.maxByOrNull { it.maxSpeed }?.takeIf { it.maxSpeed > 0 }?.let {
            out += PersonalRecord(type, RecordKind.TOP_SPEED, it.maxSpeed, it.id, it.startTime)
        }
        if (type.showsPace) {
            list.filter { it.bestPaceSecPerKm != null }.minByOrNull { it.bestPaceSecPerKm!! }?.let {
                out += PersonalRecord(type, RecordKind.BEST_PACE, it.bestPaceSecPerKm!!, it.id, it.startTime)
            }
        }
        list.maxByOrNull { it.elevationGain }?.takeIf { it.elevationGain >= 10 }?.let {
            out += PersonalRecord(type, RecordKind.MOST_CLIMB, it.elevationGain, it.id, it.startTime)
        }
        out
    }

    /** Records that the given activity set or holds. */
    fun recordsSetBy(activityId: Long, all: List<ActivityStat>): List<PersonalRecord> =
        compute(all).filter { it.activityId == activityId }
}
