package com.moscilez.track.domain.track

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS sample in the domain layer, independent of Android and Room. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val timestamp: Long,
    /** Elapsed active time since session start (pauses excluded), ms. */
    val elapsedMs: Long = 0,
    /** Speed reported by the receiver, m/s. */
    val speed: Double? = null,
    val accuracy: Double? = null,
    val bearing: Double? = null,
    val verticalAccuracy: Double? = null,
    /** Pause-separated segment index; distance is never accumulated across segments. */
    val segment: Int = 0,
)

object TrackMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine), metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distance(a: TrackPoint, b: TrackPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Total route distance; segments split by pauses are not connected. */
    fun totalDistance(points: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            if (points[i].segment == points[i - 1].segment) total += distance(points[i - 1], points[i])
        }
        return total
    }

    /** Average speed in m/s for a distance over an active duration. */
    fun averageSpeed(distanceM: Double, durationMs: Long): Double =
        if (durationMs <= 0) 0.0 else distanceM / (durationMs / 1000.0)

    /** Pace in seconds per kilometre; null when not moving. */
    fun paceSecPerKm(speedMs: Double): Double? = if (speedMs < 0.3) null else 1000.0 / speedMs

    /**
     * Maximum speed. Prefers receiver-reported speed, falling back to derived speed between
     * consecutive points spaced at least [minDtMs] apart to avoid jitter spikes.
     */
    fun maxSpeed(points: List<TrackPoint>, cap: Double = Double.MAX_VALUE, minDtMs: Long = 2000): Double {
        var best = 0.0
        for (i in points.indices) {
            val reported = points[i].speed
            if (reported != null && reported <= cap) best = max(best, reported)
            if (i > 0 && reported == null) {
                val p = points[i - 1]
                val c = points[i]
                val dt = c.timestamp - p.timestamp
                if (dt >= minDtMs && c.segment == p.segment) {
                    val v = distance(p, c) / (dt / 1000.0)
                    if (v <= cap) best = max(best, v)
                }
            }
        }
        return best
    }

    /**
     * Elevation gain and loss with hysteresis: altitude changes only count once the
     * cumulative climb or descent from the last anchor exceeds [threshold] metres. This
     * suppresses GPS altitude noise that would otherwise inflate gain.
     */
    fun elevationGainLoss(altitudes: List<Double?>, threshold: Double = 3.0): Pair<Double, Double> {
        var gain = 0.0
        var loss = 0.0
        var anchor: Double? = null
        for (alt in altitudes) {
            if (alt == null) continue
            val a = anchor
            if (a == null) {
                anchor = alt
                continue
            }
            val diff = alt - a
            if (diff >= threshold) {
                gain += diff
                anchor = alt
            } else if (diff <= -threshold) {
                loss += -diff
                anchor = alt
            }
        }
        return gain to loss
    }

    /**
     * Steepest climb and descent grade (percent) measured over windows of at least
     * [windowM] horizontal metres.
     */
    fun maxGrades(points: List<TrackPoint>, windowM: Double = 50.0): Pair<Double, Double> {
        var maxUp = 0.0
        var maxDown = 0.0
        var start = 0
        var acc = 0.0
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            acc += if (points[i].segment == points[i - 1].segment) distance(points[i - 1], points[i]) else 0.0
            cumulative[i] = acc
        }
        for (end in points.indices) {
            while (start < end && cumulative[end] - cumulative[start + 1] >= windowM) start++
            val horizontal = cumulative[end] - cumulative[start]
            val a0 = points[start].altitude
            val a1 = points[end].altitude
            if (horizontal >= windowM && a0 != null && a1 != null) {
                val grade = (a1 - a0) / horizontal * 100.0
                if (grade > maxUp) maxUp = grade
                if (-grade > maxDown) maxDown = -grade
            }
        }
        return maxUp.coerceAtMost(60.0) to maxDown.coerceAtMost(60.0)
    }
}
