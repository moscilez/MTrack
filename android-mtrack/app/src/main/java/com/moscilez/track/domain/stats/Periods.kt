package com.moscilez.track.domain.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { DAY, WEEK, MONTH, YEAR, ALL }

data class TimeRange(val start: Long, val end: Long) {
    operator fun contains(t: Long) = t in start until end
}

/** Calendar period arithmetic in the user's time zone. Weeks start on Monday. */
object Periods {
    fun range(period: StatsPeriod, now: Long, zone: ZoneId, offset: Int = 0): TimeRange {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (start, end) = when (period) {
            StatsPeriod.DAY -> today.plusDays(offset.toLong()).let { it to it.plusDays(1) }
            StatsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(offset.toLong()).let { it to it.plusWeeks(1) }
            StatsPeriod.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.plusMonths(1) }
            StatsPeriod.YEAR -> today.withDayOfYear(1).plusYears(offset.toLong()).let { it to it.plusYears(1) }
            StatsPeriod.ALL -> return TimeRange(0, Long.MAX_VALUE)
        }
        return TimeRange(startOf(start, zone), startOf(end, zone))
    }

    fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayOf(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
}
