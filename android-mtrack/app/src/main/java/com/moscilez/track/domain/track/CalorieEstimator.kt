package com.moscilez.track.domain.track

import com.moscilez.track.domain.model.ActivityType

/**
 * Approximate energy expenditure using MET values from the Compendium of Physical
 * Activities. An estimate for orientation only, not a medical measurement.
 */
object CalorieEstimator {
    fun met(type: ActivityType, avgSpeedMs: Double): Double? {
        val kmh = avgSpeedMs * 3.6
        return when (type) {
            ActivityType.RUN -> when {
                kmh < 8 -> 8.0; kmh < 9.7 -> 9.0; kmh < 11.3 -> 10.5; kmh < 12.9 -> 11.5; else -> 12.8
            }
            ActivityType.WALK -> when {
                kmh < 3.2 -> 2.5; kmh < 4.8 -> 3.0; kmh < 5.6 -> 3.8; else -> 4.8
            }
            ActivityType.BIKE -> when {
                kmh < 16 -> 4.0; kmh < 19 -> 6.8; kmh < 22.5 -> 8.0; kmh < 25.7 -> 10.0; else -> 12.0
            }
            ActivityType.HIKE -> 6.0
            else -> null
        }
    }

    /** kcal = MET * weight(kg) * moving hours. Returns null for non-sport modes. */
    fun estimate(type: ActivityType, avgSpeedMs: Double, movingMs: Long, weightKg: Double?): Double? {
        if (!type.isSport || weightKg == null || weightKg <= 0) return null
        val m = met(type, avgSpeedMs) ?: return null
        return m * weightKg * (movingMs / 3_600_000.0)
    }
}
