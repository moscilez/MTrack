package com.moscilez.track.ui.format

import android.content.Context
import com.moscilez.track.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** Locale- and unit-aware formatting. All unit labels come from string resources. */
object Formatters {
    const val M_PER_MILE = 1609.344
    const val FT_PER_M = 3.28084

    fun locale(context: Context): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun imperial(units: String) = units == "IMPERIAL"

    fun number(context: Context, value: Double, decimals: Int): String {
        val nf = NumberFormat.getNumberInstance(locale(context))
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        return nf.format(value)
    }

    /** Distance value without unit, e.g. "14,86". */
    fun distanceValue(context: Context, meters: Double, units: String): String {
        val v = if (imperial(units)) meters / M_PER_MILE else meters / 1000.0
        val decimals = if (v >= 100) 1 else 2
        return number(context, v, decimals)
    }

    fun distanceUnit(context: Context, units: String): String =
        context.getString(if (imperial(units)) R.string.unit_mi else R.string.unit_km)

    fun distance(context: Context, meters: Double, units: String): String =
        distanceValue(context, meters, units) + "\u00A0" + distanceUnit(context, units)

    fun speedValue(context: Context, ms: Double, units: String): String {
        val v = if (imperial(units)) ms * 3600 / M_PER_MILE else ms * 3.6
        return number(context, v, 1)
    }

    fun speedUnit(context: Context, units: String): String =
        context.getString(if (imperial(units)) R.string.unit_mph else R.string.unit_kmh)

    fun speed(context: Context, ms: Double, units: String) = speedValue(context, ms, units) + "\u00A0" + speedUnit(context, units)

    /** Pace value "m:ss" per km or per mile; "—" when not moving. */
    fun paceValue(secPerKm: Double?, units: String): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm > 3600) return "—"
        val s = if (imperial(units)) secPerKm * M_PER_MILE / 1000 else secPerKm
        val total = s.roundToLong()
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun paceUnit(context: Context, units: String): String =
        context.getString(if (imperial(units)) R.string.unit_pace_mi else R.string.unit_pace_km)

    fun pace(context: Context, secPerKm: Double?, units: String) = paceValue(secPerKm, units) + "\u00A0" + paceUnit(context, units)

    fun altitudeValue(context: Context, m: Double?, units: String): String {
        if (m == null) return "—"
        return number(context, if (imperial(units)) m * FT_PER_M else m, 0)
    }

    fun altitudeUnit(context: Context, units: String): String =
        context.getString(if (imperial(units)) R.string.unit_ft else R.string.unit_m)

    fun altitude(context: Context, m: Double?, units: String) = altitudeValue(context, m, units) + "\u00A0" + altitudeUnit(context, units)

    /** Clock style "00:42:18". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Human style "1 ч 22 мин" / "42 мин" / "35 с". */
    fun duration(context: Context, ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 -> context.getString(R.string.duration_hm, h, m)
            totalMin > 0 -> context.getString(R.string.duration_m, m)
            else -> context.getString(R.string.duration_s, (ms / 1000).coerceAtLeast(0))
        }
    }

    fun shortClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun dateFormatter(context: Context, pattern: String): DateTimeFormatter = when (pattern) {
        "DMY" -> DateTimeFormatter.ofPattern("dd.MM.yyyy", locale(context))
        "YMD" -> DateTimeFormatter.ofPattern("yyyy-MM-dd", locale(context))
        "MDY" -> DateTimeFormatter.ofPattern("MM/dd/yyyy", locale(context))
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(context))
    }

    fun date(context: Context, millis: Long, pattern: String = "SYSTEM", zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormatter(context, pattern).format(Instant.ofEpochMilli(millis).atZone(zone))

    fun time(context: Context, millis: Long, use24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale(context)).format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "Вт, 3 ноября" style heading. */
    fun longDay(context: Context, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = DateTimeFormatter.ofPattern("EE, d MMMM", locale(context)).format(Instant.ofEpochMilli(millis).atZone(zone))
        return s.replaceFirstChar { it.titlecase(locale(context)) }
    }

    /** Relative day label: today / yesterday / weekday / date. */
    fun relativeDay(context: Context, millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val diff = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            diff == 0L -> context.getString(R.string.day_today)
            diff == 1L -> context.getString(R.string.day_yesterday)
            diff in 2..6 -> DateTimeFormatter.ofPattern("EEEE", locale(context)).format(day).replaceFirstChar { it.titlecase(locale(context)) }
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale(context)).format(day)
        }
    }
}
