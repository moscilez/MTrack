package com.moscilez.track.domain

import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.track.SessionAccumulator
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.domain.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSpeedTest {
    private fun point(lat: Double, time: Long, speed: Double? = null, segment: Int = 0) =
        TrackPoint(lat, 37.0, timestamp = time, elapsedMs = time, speed = speed, segment = segment)

    @Test
    fun vehiclesDeriveLiveSpeedFromCoordinatesAtOneSecondIntervals() {
        for (type in listOf(ActivityType.CAR, ActivityType.MOTO)) {
            val accumulator = SessionAccumulator(type)
            val first = point(55.0, 0)
            val second = point(55.0001, 1000)
            val third = point(55.0003, 2000)
            accumulator.add(first)
            assertEquals(0.0, accumulator.currentSpeed, 0.0)
            accumulator.add(second)
            assertEquals(TrackMath.distance(first, second), accumulator.currentSpeed, 1e-6)
            accumulator.add(third)
            assertEquals(TrackMath.distance(second, third), accumulator.currentSpeed, 1e-6)
            assertEquals(accumulator.currentSpeed, accumulator.summary().maxSpeed, 1e-6)
            accumulator.add(point(third.lat, 3000))
            assertEquals(0.0, accumulator.currentSpeed, 0.0)
        }
    }

    @Test
    fun providerSpeedTakesPriorityAndCanReportAStop() {
        val accumulator = SessionAccumulator(ActivityType.CAR)
        accumulator.add(point(55.0, 0, speed = 5.0))
        assertEquals(5.0, accumulator.currentSpeed, 0.0)
        accumulator.add(point(55.0001, 1000, speed = 8.0))
        assertEquals(8.0, accumulator.currentSpeed, 0.0)
        accumulator.add(point(55.0002, 2000, speed = 0.0))
        assertEquals(0.0, accumulator.currentSpeed, 0.0)
    }

    @Test
    fun speedDoesNotBridgePauseSegments() {
        val accumulator = SessionAccumulator(ActivityType.MOTO)
        accumulator.add(point(55.0, 0))
        accumulator.add(point(55.0001, 1000))
        accumulator.add(point(56.0, 2000, segment = 1))
        assertEquals(0.0, accumulator.currentSpeed, 0.0)
        val next = point(56.0001, 3000, segment = 1)
        accumulator.add(next)
        assertEquals(TrackMath.distance(56.0, 37.0, next.lat, next.lon), accumulator.currentSpeed, 1e-6)
    }
}
