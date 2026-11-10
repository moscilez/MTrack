package com.moscilez.track.domain

import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.track.FilterResult
import com.moscilez.track.domain.track.GpsFilter
import com.moscilez.track.domain.track.RejectReason
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.domain.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMathTest {
    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        val d = TrackMath.distance(55.0, 37.0, 56.0, 37.0)
        assertEquals(111_195.0, d, 50.0)
    }

    @Test
    fun totalDistanceDoesNotBridgePauseSegments() {
        val a = TrackPoint(55.0, 37.0, timestamp = 0, segment = 0)
        val b = TrackPoint(55.001, 37.0, timestamp = 1000, segment = 0)
        val c = TrackPoint(55.010, 37.0, timestamp = 2000, segment = 1)
        val d = TrackPoint(55.011, 37.0, timestamp = 3000, segment = 1)
        val total = TrackMath.totalDistance(listOf(a, b, c, d))
        assertEquals(TrackMath.distance(a, b) + TrackMath.distance(c, d), total, 0.001)
    }

    @Test
    fun averageSpeedAndPace() {
        assertEquals(2.5, TrackMath.averageSpeed(5000.0, 2_000_000), 1e-9)
        assertEquals(0.0, TrackMath.averageSpeed(100.0, 0), 1e-9)
        assertEquals(400.0, TrackMath.paceSecPerKm(2.5)!!, 1e-9)
        assertNull(TrackMath.paceSecPerKm(0.1))
    }
}

class GpsFilterTest {
    private fun p(lat: Double, t: Long, acc: Double? = 5.0, seg: Int = 0) =
        TrackPoint(lat, 37.0, timestamp = t, accuracy = acc, segment = seg)

    @Test
    fun rejectsLowAccuracy() {
        val f = GpsFilter(ActivityType.RUN)
        assertEquals(FilterResult.Rejected(RejectReason.LOW_ACCURACY), f.offer(p(55.0, 0, acc = 80.0)))
    }

    @Test
    fun acceptsFirstThenRejectsDuplicateAndOutOfOrder() {
        val f = GpsFilter(ActivityType.RUN)
        val first = f.offer(p(55.0, 10_000))
        assertTrue(first is FilterResult.Accepted)
        assertEquals(0.0, (first as FilterResult.Accepted).distanceFromPrevious, 0.0)
        assertEquals(FilterResult.Rejected(RejectReason.DUPLICATE), f.offer(p(55.0001, 10_000)))
        assertEquals(FilterResult.Rejected(RejectReason.OUT_OF_ORDER), f.offer(p(55.0001, 5_000)))
    }

    @Test
    fun rejectsImpossibleSpeedForRunner() {
        val f = GpsFilter(ActivityType.RUN)
        f.offer(p(55.0, 0))
        // ~1.1 km in 2 s is far beyond running speed.
        assertEquals(FilterResult.Rejected(RejectReason.IMPOSSIBLE_SPEED), f.offer(p(55.01, 2_000)))
    }

    @Test
    fun reanchorsAfterFiveImpossibleJumps() {
        val f = GpsFilter(ActivityType.RUN)
        f.offer(p(55.0, 0))
        repeat(4) { i -> assertTrue(f.offer(p(55.01, (i + 1) * 1000L)) is FilterResult.Rejected) }
        assertTrue(f.offer(p(55.01, 5_000)) is FilterResult.Accepted)
    }

    @Test
    fun rejectsJitterWhenStanding() {
        val f = GpsFilter(ActivityType.WALK)
        f.offer(p(55.0, 0))
        // ~1 m away, 1 s later, no reported speed.
        assertEquals(FilterResult.Rejected(RejectReason.JITTER), f.offer(p(55.000009, 1_000)))
    }

    @Test
    fun newSegmentStartsAtZeroDistance() {
        val f = GpsFilter(ActivityType.BIKE)
        f.offer(p(55.0, 0))
        val r = f.offer(p(55.05, 600_000, seg = 1))
        assertEquals(0.0, (r as FilterResult.Accepted).distanceFromPrevious, 0.0)
    }

    @Test
    fun acceptsNormalMovementWithDistance() {
        val f = GpsFilter(ActivityType.RUN)
        f.offer(p(55.0, 0))
        val r = f.offer(p(55.0001, 4_000)) as FilterResult.Accepted
        assertEquals(11.1, r.distanceFromPrevious, 0.2)
    }
}
