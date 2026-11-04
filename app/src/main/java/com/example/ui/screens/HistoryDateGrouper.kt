package com.example.ui.screens

import java.util.Calendar
import java.util.TimeZone

/**
 * Calendar-based time grouping categories for listening history.
 */
enum class HistoryTimeGroup(val title: String, val order: Int) {
    TODAY("Today", 0),
    YESTERDAY("Yesterday", 1),
    THIS_WEEK("Earlier This Week", 2),
    THIS_MONTH("Earlier This Month", 3),
    OLDER("Older", 4)
}

/**
 * Categorizes a track playback timestamp into human-friendly calendar groups.
 *
 * Accurately handles:
 * - Real calendar dates (not simple 24h mathematical division).
 * - Leap years (e.g. Feb 28/29 transitions).
 * - Month boundaries (e.g. 1st of month to previous month's final day).
 * - Year transitions (Dec 31 to Jan 1).
 * - Daylight Saving Time (DST) 23h/25h transitions via midnight truncation.
 * - Clock skew / future timestamps safely mapped to Today.
 *
 * @param timestamp Milliseconds since epoch for the historical event.
 * @param nowMs Milliseconds since epoch representing current time (default System.currentTimeMillis()).
 * @param timeZone Local timezone used for calendar boundary evaluations.
 */
fun getHistoryGroup(
    timestamp: Long,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): HistoryTimeGroup {
    val nowCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
    val itemCal = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }

    // Truncate both timestamps to midnight (00:00:00.000) in the specified timezone
    val cNow = (nowCal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val cItem = (itemCal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val diffMillis = cNow.timeInMillis - cItem.timeInMillis
    // 24h = 86,400,000ms. Math.round smoothly handles 23h and 25h DST transition days.
    val dayDiff = Math.round(diffMillis.toDouble() / (24 * 60 * 60 * 1000L))

    val isSameMonthAndYear = nowCal.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.MONTH) == itemCal.get(Calendar.MONTH)

    return when {
        dayDiff <= 0L -> HistoryTimeGroup.TODAY
        dayDiff == 1L -> HistoryTimeGroup.YESTERDAY
        dayDiff in 2L..6L -> HistoryTimeGroup.THIS_WEEK
        isSameMonthAndYear -> HistoryTimeGroup.THIS_MONTH
        else -> HistoryTimeGroup.OLDER
    }
}
