package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryDateGroupingTest {

    private val utcZone = TimeZone.getTimeZone("UTC")

    private fun createCalendar(
        year: Int,
        monthZeroIndexed: Int,
        dayOfMonth: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0
    ): Calendar {
        return Calendar.getInstance(utcZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroIndexed)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun testSameDayIsToday() {
        val nowCal = createCalendar(2026, Calendar.MAY, 15, 18, 30)
        val itemCal = createCalendar(2026, Calendar.MAY, 15, 9, 15)

        val group = getHistoryGroup(
            timestamp = itemCal.timeInMillis,
            nowMs = nowCal.timeInMillis,
            timeZone = utcZone
        )
        assertEquals(HistoryTimeGroup.TODAY, group)
    }

    @Test
    fun testFutureTimestampDueToClockDriftIsToday() {
        val nowCal = createCalendar(2026, Calendar.MAY, 15, 12, 0)
        val itemCal = createCalendar(2026, Calendar.MAY, 15, 12, 5) // 5 minutes in future

        val group = getHistoryGroup(
            timestamp = itemCal.timeInMillis,
            nowMs = nowCal.timeInMillis,
            timeZone = utcZone
        )
        assertEquals(HistoryTimeGroup.TODAY, group)
    }

    @Test
    fun testMidnightBoundaries() {
        // Now is 00:00:05 on Jan 1
        val nowCal = createCalendar(2026, Calendar.JANUARY, 1, 0, 0, 5)
        // Item 1 is 23:59:55 on Dec 31 (10 seconds earlier, but yesterday)
        val itemYesterday = createCalendar(2025, Calendar.DECEMBER, 31, 23, 59, 55)
        // Item 2 is 00:00:01 on Jan 1 (4 seconds earlier, same day)
        val itemToday = createCalendar(2026, Calendar.JANUARY, 1, 0, 0, 1)

        val groupYesterday = getHistoryGroup(
            timestamp = itemYesterday.timeInMillis,
            nowMs = nowCal.timeInMillis,
            timeZone = utcZone
        )
        assertEquals(HistoryTimeGroup.YESTERDAY, groupYesterday)

        val groupToday = getHistoryGroup(
            timestamp = itemToday.timeInMillis,
            nowMs = nowCal.timeInMillis,
            timeZone = utcZone
        )
        assertEquals(HistoryTimeGroup.TODAY, groupToday)
    }

    @Test
    fun testYearBoundaryDecJanTransition() {
        val nowCal = createCalendar(2026, Calendar.JANUARY, 1, 14, 0)

        // Yesterday was Dec 31, 2025
        val itemDec31 = createCalendar(2025, Calendar.DECEMBER, 31, 20, 0)
        assertEquals(
            HistoryTimeGroup.YESTERDAY,
            getHistoryGroup(itemDec31.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // 3 days ago was Dec 29, 2025 -> Earlier This Week
        val itemDec29 = createCalendar(2025, Calendar.DECEMBER, 29, 14, 0)
        assertEquals(
            HistoryTimeGroup.THIS_WEEK,
            getHistoryGroup(itemDec29.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // 6 days ago was Dec 26, 2025 -> Earlier This Week
        val itemDec26 = createCalendar(2025, Calendar.DECEMBER, 26, 14, 0)
        assertEquals(
            HistoryTimeGroup.THIS_WEEK,
            getHistoryGroup(itemDec26.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // 10 days ago was Dec 22, 2025 -> Not same month as Jan, so Older
        val itemDec22 = createCalendar(2025, Calendar.DECEMBER, 22, 14, 0)
        assertEquals(
            HistoryTimeGroup.OLDER,
            getHistoryGroup(itemDec22.timeInMillis, nowCal.timeInMillis, utcZone)
        )
    }

    @Test
    fun testLeapYearFeb29Transition() {
        // 2024 was a leap year: Feb 29 existed
        val nowCal = createCalendar(2024, Calendar.MARCH, 1, 10, 0)

        val itemFeb29 = createCalendar(2024, Calendar.FEBRUARY, 29, 15, 0)
        assertEquals(
            HistoryTimeGroup.YESTERDAY,
            getHistoryGroup(itemFeb29.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        val itemFeb28 = createCalendar(2024, Calendar.FEBRUARY, 28, 15, 0)
        assertEquals(
            HistoryTimeGroup.THIS_WEEK,
            getHistoryGroup(itemFeb28.timeInMillis, nowCal.timeInMillis, utcZone)
        )
    }

    @Test
    fun testNonLeapYearFeb28Transition() {
        // 2023 was NOT a leap year: Feb had 28 days
        val nowCal = createCalendar(2023, Calendar.MARCH, 1, 10, 0)

        val itemFeb28 = createCalendar(2023, Calendar.FEBRUARY, 28, 22, 0)
        assertEquals(
            HistoryTimeGroup.YESTERDAY,
            getHistoryGroup(itemFeb28.timeInMillis, nowCal.timeInMillis, utcZone)
        )
    }

    @Test
    fun testMonthBoundaryTransition() {
        val nowCal = createCalendar(2025, Calendar.MAY, 1, 16, 0)

        // April 30 is yesterday
        val itemApr30 = createCalendar(2025, Calendar.APRIL, 30, 23, 0)
        assertEquals(
            HistoryTimeGroup.YESTERDAY,
            getHistoryGroup(itemApr30.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // April 27 is 4 days ago -> Earlier This Week
        val itemApr27 = createCalendar(2025, Calendar.APRIL, 27, 10, 0)
        assertEquals(
            HistoryTimeGroup.THIS_WEEK,
            getHistoryGroup(itemApr27.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // April 20 is 11 days ago in previous month -> Older
        val itemApr20 = createCalendar(2025, Calendar.APRIL, 20, 10, 0)
        assertEquals(
            HistoryTimeGroup.OLDER,
            getHistoryGroup(itemApr20.timeInMillis, nowCal.timeInMillis, utcZone)
        )
    }

    @Test
    fun testEarlierThisMonthVsOlder() {
        val nowCal = createCalendar(2025, Calendar.OCTOBER, 25, 12, 0)

        // October 15 is 10 days ago, same month & year -> Earlier This Month
        val itemOct15 = createCalendar(2025, Calendar.OCTOBER, 15, 12, 0)
        assertEquals(
            HistoryTimeGroup.THIS_MONTH,
            getHistoryGroup(itemOct15.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // October 1 is 24 days ago, same month & year -> Earlier This Month
        val itemOct1 = createCalendar(2025, Calendar.OCTOBER, 1, 12, 0)
        assertEquals(
            HistoryTimeGroup.THIS_MONTH,
            getHistoryGroup(itemOct1.timeInMillis, nowCal.timeInMillis, utcZone)
        )

        // September 30 is in prior month -> Older
        val itemSep30 = createCalendar(2025, Calendar.SEPTEMBER, 30, 12, 0)
        assertEquals(
            HistoryTimeGroup.OLDER,
            getHistoryGroup(itemSep30.timeInMillis, nowCal.timeInMillis, utcZone)
        )
    }

    @Test
    fun testDaylightSavingTimeTransition() {
        // DST change occurs: 23 hours or 25 hours between calendar midnights
        val tz = TimeZone.getTimeZone("America/New_York")
        val nowCal = Calendar.getInstance(tz).apply {
            set(2025, Calendar.NOVEMBER, 3, 10, 0, 0) // Day after fall back (25-hour day)
            set(Calendar.MILLISECOND, 0)
        }
        val itemCal = Calendar.getInstance(tz).apply {
            set(2025, Calendar.NOVEMBER, 2, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        assertEquals(
            HistoryTimeGroup.YESTERDAY,
            getHistoryGroup(itemCal.timeInMillis, nowCal.timeInMillis, tz)
        )
    }
}
