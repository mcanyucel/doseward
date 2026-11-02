package com.mustafacanyucel.doseward.core

import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {

    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun everyOtherDayCountsFromAnchor() {
        val r = Recurrence.EveryNDays(2, anchor = monday)
        assertTrue(r.occursOn(monday))
        assertFalse(r.occursOn(monday.plusDays(1)))
        assertTrue(r.occursOn(monday.plusDays(2)))
        // The pattern also holds before the anchor.
        assertTrue(r.occursOn(monday.minusDays(2)))
        assertFalse(r.occursOn(monday.minusDays(1)))
    }

    @Test
    fun dailyOccursEveryDay() {
        val r = Recurrence.EveryNDays(1, anchor = monday)
        (0L..10L).forEach { assertTrue(r.occursOn(monday.plusDays(it))) }
    }

    @Test
    fun weekdaysMatchChosenDaysOnly() {
        val r = Recurrence.Weekdays(setOf(MONDAY, THURSDAY))
        assertTrue(r.occursOn(monday))
        assertFalse(r.occursOn(monday.plusDays(1)))
        assertTrue(r.occursOn(monday.plusDays(3)))
        assertTrue(r.occursOn(monday.plusDays(7)))
    }

    @Test
    fun cycleSizesForDailyRate() {
        val everyOther = Recurrence.EveryNDays(2, monday)
        assertEquals(2, everyOther.cycleDays)
        assertEquals(1, everyOther.doseDaysPerCycle)

        val twiceAWeek = Recurrence.Weekdays(setOf(MONDAY, THURSDAY))
        assertEquals(7, twiceAWeek.cycleDays)
        assertEquals(2, twiceAWeek.doseDaysPerCycle)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDayIntervalIsRejected() {
        Recurrence.EveryNDays(0, monday)
    }
}