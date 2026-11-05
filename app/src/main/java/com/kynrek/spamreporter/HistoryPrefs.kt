package com.kynrek.spamreporter

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** How the History tab of "Choose spam senders" is grouped, and how far back it goes. */
object HistoryPrefs {
    enum class Period(val label: String, val unit: ChronoUnit?) {
        NONE("None", null), DAY("Day", ChronoUnit.DAYS), WEEK("Week", ChronoUnit.WEEKS), MONTH("Month", ChronoUnit.MONTHS), YEAR("Year", ChronoUnit.YEARS)
    }
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Group by (default: week). */
    fun grouping(c: Context) = runCatching { Period.valueOf(prefs(c).getString("hist_group", null)!!) }.getOrDefault(Period.WEEK)
    fun setGrouping(c: Context, p: Period) = prefs(c).edit().putString("hist_group", p.name).apply()

    /** Hide senders whose last text is older than [cutoffCount] x [cutoffUnit] (default: 3 months; NONE = show all). */
    fun cutoffUnit(c: Context) = runCatching { Period.valueOf(prefs(c).getString("hist_cut_unit", null)!!) }.getOrDefault(Period.MONTH)
    fun cutoffCount(c: Context) = prefs(c).getInt("hist_cut_n", 3).coerceIn(1, 99)
    fun setCutoff(c: Context, unit: Period, n: Int) = prefs(c).edit().putString("hist_cut_unit", unit.name).putInt("hist_cut_n", n.coerceIn(1, 99)).apply()
    fun cutoffLabel(c: Context) = cutoffUnit(c).let { u -> if (u == Period.NONE) "no limit" else
        "${cutoffCount(c)} ${u.label.lowercase()}${if (cutoffCount(c) == 1) "" else "s"}" }

    /** Epoch millis; senders whose newest text is older are hidden. 0 = no cutoff. */
    fun cutoffMillis(c: Context): Long {
        val u = cutoffUnit(c).unit ?: return 0
        return ZonedDateTime.now().minus(cutoffCount(c).toLong(), u).toInstant().toEpochMilli()
    }

    /** Section heading for a sender whose newest text is at [ts] (null when not grouping). */
    fun groupLabel(p: Period, ts: Long): String? {
        val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (p) {
            Period.NONE -> null
            Period.DAY -> when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"
                else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.US)) }
            Period.WEEK -> { val wf = WeekFields.of(Locale.US); val start = d.with(wf.dayOfWeek(), 1)
                when (start) { today.with(wf.dayOfWeek(), 1) -> "This week"; today.with(wf.dayOfWeek(), 1).minusWeeks(1) -> "Last week"
                    else -> "Week of " + start.format(DateTimeFormatter.ofPattern(if (start.year == today.year) "MMM d" else "MMM d, yyyy", Locale.US)) } }
            Period.MONTH -> d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
            Period.YEAR -> d.year.toString()
        }
    }
}
