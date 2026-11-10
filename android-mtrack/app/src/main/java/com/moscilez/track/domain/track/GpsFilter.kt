package com.moscilez.track.domain.track

import com.moscilez.track.domain.model.ActivityType

/** Why a GPS fix was discarded. Surfaced in diagnostics. */
enum class RejectReason { LOW_ACCURACY, DUPLICATE, OUT_OF_ORDER, IMPOSSIBLE_SPEED, JITTER }

sealed interface FilterResult {
    data class Accepted(val point: TrackPoint, val distanceFromPrevious: Double) : FilterResult
    data class Rejected(val reason: RejectReason) : FilterResult
}

/**
 * Stateful filter that rejects inaccurate fixes, duplicates, implausible jumps and
 * sub-metre jitter before a point is stored. One instance per recording.
 */
class GpsFilter(
    private val type: ActivityType,
    private val maxAccuracyM: Double = 35.0,
    private val minDistanceM: Double = 2.0,
) {
    private var last: TrackPoint? = null
    private var consecutiveSpeedRejects = 0

    fun reset() {
        last = null
        consecutiveSpeedRejects = 0
    }

    /** Seeds the filter after process restart so the first new fix is compared correctly. */
    fun seed(point: TrackPoint) {
        last = point
    }

    fun offer(point: TrackPoint): FilterResult {
        val acc = point.accuracy
        if (acc != null && acc > maxAccuracyM) return FilterResult.Rejected(RejectReason.LOW_ACCURACY)
        val prev = last
        if (prev == null || prev.segment != point.segment) {
            last = point
            return FilterResult.Accepted(point, 0.0)
        }
        if (point.timestamp == prev.timestamp) return FilterResult.Rejected(RejectReason.DUPLICATE)
        if (point.timestamp < prev.timestamp) return FilterResult.Rejected(RejectReason.OUT_OF_ORDER)

        val d = TrackMath.distance(prev, point)
        val dt = (point.timestamp - prev.timestamp) / 1000.0
        val implied = d / dt
        // Allow the error margin of both fixes before declaring a jump impossible.
        val margin = ((prev.accuracy ?: 10.0) + (acc ?: 10.0)) / dt
        if (implied > type.maxPlausibleSpeed + margin) {
            consecutiveSpeedRejects++
            // After repeated rejects the previous anchor was likely the outlier: re-anchor.
            if (consecutiveSpeedRejects >= 5) {
                consecutiveSpeedRejects = 0
                last = point
                return FilterResult.Accepted(point, 0.0)
            }
            return FilterResult.Rejected(RejectReason.IMPOSSIBLE_SPEED)
        }
        consecutiveSpeedRejects = 0
        if (d < minDistanceM && (point.speed ?: 0.0) < type.stopSpeed && dt < 30) {
            return FilterResult.Rejected(RejectReason.JITTER)
        }
        last = point
        return FilterResult.Accepted(point, d)
    }
}
