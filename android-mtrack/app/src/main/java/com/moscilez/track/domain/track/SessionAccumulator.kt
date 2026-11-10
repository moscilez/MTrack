package com.moscilez.track.domain.track

import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.PeakKind
import kotlin.math.abs

data class Stop(
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val startTimestamp: Long,
    val lat: Double,
    val lon: Double,
) {
    val durationMs: Long get() = endElapsedMs - startElapsedMs
}

data class Split(
    val index: Int,
    val distanceM: Double,
    val durationMs: Long,
    val elevationDelta: Double,
) {
    val avgSpeed: Double get() = TrackMath.averageSpeed(distanceM, durationMs)
}

data class Peak(val kind: PeakKind, val value: Double, val elapsedMs: Long, val lat: Double, val lon: Double)

data class SessionSummary(
    val distanceM: Double,
    val maxSpeed: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val maxAltitude: Double?,
    val minAltitude: Double?,
    val maxClimbGrade: Double,
    val maxDescentGrade: Double,
    val stops: List<Stop>,
    val stoppedMs: Long,
    val splits: List<Split>,
    val peaks: List<Peak>,
    val overSpeedMs: Long,
    val maxAccel: Double,
    val maxDecel: Double,
    val pointCount: Int,
    val lastPoint: TrackPoint?,
    val currentAltitude: Double?,
) {
    /** Fastest completed full split converted to seconds per kilometre. */
    val bestSplitPace: Double?
        get() = splits.filter { it.distanceM >= 999.0 && it.durationMs > 0 }
            .minOfOrNull { it.durationMs / 1000.0 / (it.distanceM / 1000.0) }
}

/**
 * Incremental, O(1)-memory-per-point analyser. Used both live by the tracking service and
 * for final analysis by streaming stored points in chunks, so long routes never need to be
 * fully loaded. The same code path guarantees live and saved numbers agree.
 */
class SessionAccumulator(
    private val type: ActivityType,
    private val overSpeedThreshold: Double? = null,
    private val splitLengthM: Double = 1000.0,
    private val elevationThreshold: Double = 3.0,
    private val gradeWindowM: Double = 50.0,
) {
    private var distance = 0.0
    private var last: TrackPoint? = null
    private var count = 0

    private var maxSpeed = 0.0
    private var maxSpeedPeak: Peak? = null

    private var altAnchor: Double? = null
    private var gain = 0.0
    private var loss = 0.0
    private var maxAlt: Peak? = null
    private var minAlt: Peak? = null
    private var currentAlt: Double? = null

    private val gradeWindow = ArrayDeque<Pair<Double, Double>>()
    private var maxClimb: Peak? = null
    private var maxDescent: Peak? = null

    private var stationaryStart: TrackPoint? = null
    private val stops = mutableListOf<Stop>()
    private var stoppedMs = 0L

    private val splits = mutableListOf<Split>()
    private var splitStartElapsed = 0L
    private var splitStartDistance = 0.0
    private var splitStartAlt: Double? = null

    private var overSpeedMs = 0L
    private var maxAccel: Peak? = null
    private var maxDecel: Peak? = null

    fun add(p: TrackPoint) {
        val prev = last
        count++
        currentAlt = p.altitude ?: currentAlt
        if (prev == null) {
            splitStartElapsed = p.elapsedMs
            splitStartAlt = p.altitude
        }
        trackAltitude(p)

        if (prev != null && prev.segment == p.segment) {
            val d = TrackMath.distance(prev, p)
            val dtMs = p.elapsedMs - prev.elapsedMs
            val wallDt = (p.timestamp - prev.timestamp).coerceAtLeast(1)
            distance += d
            val derived = d / (wallDt / 1000.0)
            val speed = p.speed ?: if (wallDt >= 2000) derived else null

            if (speed != null && speed <= type.maxPlausibleSpeed && speed > maxSpeed) {
                maxSpeed = speed
                maxSpeedPeak = Peak(PeakKind.MAX_SPEED, speed, p.elapsedMs, p.lat, p.lon)
            }
            trackStops(prev, p, derived, speed)
            trackGrade(p)
            trackSplits(p)
            if (overSpeedThreshold != null && speed != null && speed > overSpeedThreshold && dtMs in 1..30_000) {
                overSpeedMs += dtMs
            }
            trackAcceleration(prev, p, wallDt)
        } else if (prev != null) {
            // New segment after a pause: close any open stop and restart the grade window.
            closeStop(prev)
            gradeWindow.clear()
        }
        last = p
    }

    private fun trackAltitude(p: TrackPoint) {
        val alt = p.altitude ?: return
        if (maxAlt == null || alt > maxAlt!!.value) maxAlt = Peak(PeakKind.MAX_ALTITUDE, alt, p.elapsedMs, p.lat, p.lon)
        if (minAlt == null || alt < minAlt!!.value) minAlt = Peak(PeakKind.MIN_ALTITUDE, alt, p.elapsedMs, p.lat, p.lon)
        val anchor = altAnchor
        if (anchor == null) {
            altAnchor = alt
            return
        }
        val diff = alt - anchor
        if (diff >= elevationThreshold) {
            gain += diff; altAnchor = alt
        } else if (diff <= -elevationThreshold) {
            loss += -diff; altAnchor = alt
        }
    }

    private fun trackStops(prev: TrackPoint, p: TrackPoint, derived: Double, speed: Double?) {
        val effective = minOf(derived, speed ?: derived)
        if (effective < type.stopSpeed) {
            if (stationaryStart == null) stationaryStart = prev
        } else {
            closeStop(prev)
        }
    }

    private fun closeStop(endPoint: TrackPoint) {
        val start = stationaryStart ?: return
        stationaryStart = null
        val duration = endPoint.elapsedMs - start.elapsedMs
        if (duration >= type.minStopMs) {
            stops += Stop(start.elapsedMs, endPoint.elapsedMs, start.timestamp, start.lat, start.lon)
            stoppedMs += duration
        }
    }

    private fun trackGrade(p: TrackPoint) {
        val alt = p.altitude ?: return
        gradeWindow.addLast(distance to alt)
        while (gradeWindow.size > 2 && distance - gradeWindow[1].first >= gradeWindowM) gradeWindow.removeFirst()
        val first = gradeWindow.first()
        val horizontal = distance - first.first
        if (horizontal >= gradeWindowM) {
            val grade = ((alt - first.second) / horizontal * 100.0).coerceIn(-60.0, 60.0)
            if (grade > (maxClimb?.value ?: 0.0)) maxClimb = Peak(PeakKind.MAX_CLIMB, grade, p.elapsedMs, p.lat, p.lon)
            if (-grade > (maxDescent?.value ?: 0.0)) maxDescent = Peak(PeakKind.MAX_DESCENT, -grade, p.elapsedMs, p.lat, p.lon)
        }
    }

    private fun trackSplits(p: TrackPoint) {
        while (distance - splitStartDistance >= splitLengthM) {
            val duration = p.elapsedMs - splitStartElapsed
            val delta = if (p.altitude != null && splitStartAlt != null) p.altitude - splitStartAlt!! else 0.0
            splits += Split(splits.size + 1, splitLengthM, duration, delta)
            splitStartDistance += splitLengthM
            splitStartElapsed = p.elapsedMs
            splitStartAlt = p.altitude
        }
    }

    private fun trackAcceleration(prev: TrackPoint, p: TrackPoint, wallDt: Long) {
        if (!type.isVehicle) return
        val v0 = prev.speed ?: return
        val v1 = p.speed ?: return
        if (wallDt !in 1000..5000) return
        val a = (v1 - v0) / (wallDt / 1000.0)
        // Values beyond ~1.5 g are GPS artefacts for road vehicles.
        if (abs(a) > 15.0) return
        if (a > (maxAccel?.value ?: 0.0)) maxAccel = Peak(PeakKind.MAX_ACCEL, a, p.elapsedMs, p.lat, p.lon)
        if (-a > (maxDecel?.value ?: 0.0)) maxDecel = Peak(PeakKind.MAX_DECEL, -a, p.elapsedMs, p.lat, p.lon)
    }

    fun summary(): SessionSummary {
        // Include an open stop that is still ongoing at the end of the data.
        val openStop = stationaryStart?.let { s ->
            val end = last ?: return@let null
            val d = end.elapsedMs - s.elapsedMs
            if (d >= type.minStopMs) Stop(s.elapsedMs, end.elapsedMs, s.timestamp, s.lat, s.lon) else null
        }
        val allStops = if (openStop != null) stops + openStop else stops.toList()
        val partial = distance - splitStartDistance
        val lastP = last
        val allSplits = if (partial >= 50 && lastP != null) {
            val delta = if (lastP.altitude != null && splitStartAlt != null) lastP.altitude - splitStartAlt!! else 0.0
            splits + Split(splits.size + 1, partial, lastP.elapsedMs - splitStartElapsed, delta)
        } else splits.toList()
        return SessionSummary(
            distanceM = distance,
            maxSpeed = maxSpeed,
            elevationGain = gain,
            elevationLoss = loss,
            maxAltitude = maxAlt?.value,
            minAltitude = minAlt?.value,
            maxClimbGrade = maxClimb?.value ?: 0.0,
            maxDescentGrade = maxDescent?.value ?: 0.0,
            stops = allStops,
            stoppedMs = stoppedMs + (openStop?.durationMs ?: 0),
            splits = allSplits,
            peaks = listOfNotNull(maxSpeedPeak, maxAlt, minAlt, maxClimb, maxDescent, maxAccel, maxDecel),
            overSpeedMs = overSpeedMs,
            maxAccel = maxAccel?.value ?: 0.0,
            maxDecel = maxDecel?.value ?: 0.0,
            pointCount = count,
            lastPoint = lastP,
            currentAltitude = currentAlt,
        )
    }
}
