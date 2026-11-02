package com.mustafacanyucel.doseward.core

import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleEngineTest {

    private val istanbul = ZoneId.of("Europe/Istanbul") // no DST
    private val berlin = ZoneId.of("Europe/Berlin")     // DST: Mar 29 and Oct 25, 2026

    private fun at(local: String, zone: ZoneId = istanbul): Instant =
        LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun daily(
        vararg times: String,
        id: Long = 1,
        start: String = "2026-11-02",
        end: String? = null,
        effectiveFrom: Instant = Instant.EPOCH,
        effectiveUntil: Instant? = null,
    ) = ScheduleVersion(
        id = id,
        medicationId = 7,
        timesOfDay = times.map(LocalTime::parse),
        recurrence = Recurrence.EveryNDays(1, LocalDate.parse(start)),
        dosePerIntake = Amount(1000),
        startDate = LocalDate.parse(start),
        endDate = end?.let(LocalDate::parse),
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveUntil,
    )

    private fun List<PlannedDose>.locals() = map { it.scheduledLocal.toString() }

    @Test
    fun threeTimesDailyInOrder() {
        val v = daily("08:00", "14:00", "20:00")
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-11-02T00:00"), at("2026-11-03T23:59"), istanbul)
        assertEquals(
            listOf(
                "2026-11-02T08:00", "2026-11-02T14:00", "2026-11-02T20:00",
                "2026-11-03T08:00", "2026-11-03T14:00", "2026-11-03T20:00",
            ),
            doses.locals(),
        )
        assertEquals(at("2026-11-02T08:00"), doses.first().scheduledAt)
    }

    @Test
    fun rangeExcludesStartAndIncludesEnd() {
        val v = daily("08:00", "14:00", "20:00")
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-11-02T08:00"), at("2026-11-02T20:00"), istanbul)
        assertEquals(listOf("2026-11-02T14:00", "2026-11-02T20:00"), doses.locals())
    }

    @Test
    fun nothingBeforeStartDateOrAfterEndDate() {
        val v = daily("08:00", start = "2026-11-02", end = "2026-11-03")
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-10-30T00:00"), at("2026-11-10T00:00"), istanbul)
        assertEquals(listOf("2026-11-02T08:00", "2026-11-03T08:00"), doses.locals())
        // An ended schedule has no next dose, and the search stops instead of looping forever.
        assertNull(ScheduleEngine.nextDoseAfter(listOf(v), at("2026-11-03T08:00"), istanbul))
    }

    @Test
    fun noDosesBeforeTheVersionExisted() {
        // Medication added at 10:00: today's 08:00 dose is not created.
        val v = daily("08:00", "14:00", effectiveFrom = at("2026-11-02T10:00"))
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-11-02T00:00"), at("2026-11-03T12:00"), istanbul)
        assertEquals(listOf("2026-11-02T14:00", "2026-11-03T08:00"), doses.locals())
    }

    @Test
    fun editSplitsDosesBetweenVersions() {
        // On Nov 3 at 12:00 the user moves 08:00/20:00 to 09:00/21:00.
        val edit = at("2026-11-03T12:00")
        val old = daily("08:00", "20:00", id = 1, effectiveUntil = edit)
        val new = daily("09:00", "21:00", id = 2, effectiveFrom = edit)
        val doses = ScheduleEngine.dosesBetween(listOf(old, new), at("2026-11-03T00:00"), at("2026-11-04T23:59"), istanbul)
        assertEquals(
            listOf("2026-11-03T08:00", "2026-11-03T21:00", "2026-11-04T09:00", "2026-11-04T21:00"),
            doses.locals(),
        )
        assertEquals(listOf(1L, 2L, 2L, 2L), doses.map { it.scheduleVersionId })
    }

    @Test
    fun springForwardGapRingsOnceAnHourLater() {
        // Berlin, Mar 29 2026: clocks jump from 02:00 to 03:00, so 02:30 doesn't exist.
        val v = daily("02:30", start = "2026-03-28")
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-03-29T00:00", berlin), at("2026-03-29T23:59", berlin), berlin)
        assertEquals(1, doses.size)
        assertEquals("2026-03-29T02:30", doses.single().scheduledLocal.toString())
        assertEquals(at("2026-03-29T03:30", berlin), doses.single().scheduledAt)
    }

    @Test
    fun fallBackOverlapRingsOnceAtFirstOccurrence() {
        // Berlin, Oct 25 2026: clocks go from 03:00 back to 02:00, so 02:30 happens twice.
        val v = daily("02:30", start = "2026-10-24")
        val doses = ScheduleEngine.dosesBetween(listOf(v), at("2026-10-25T00:00", berlin), at("2026-10-25T23:59", berlin), berlin)
        assertEquals(1, doses.size)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), doses.single().scheduledAt) // 02:30 summer time
    }

    @Test
    fun doseTimesFollowTheTimeZone() {
        // Same 08:00 schedule: Istanbul is UTC+3, Berlin is UTC+1 in November.
        val v = daily("08:00")
        val after = Instant.parse("2026-11-02T00:00:00Z")
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), ScheduleEngine.nextDoseAfter(listOf(v), after, istanbul)!!.scheduledAt)
        assertEquals(Instant.parse("2026-11-02T07:00:00Z"), ScheduleEngine.nextDoseAfter(listOf(v), after, berlin)!!.scheduledAt)
    }

    @Test
    fun nextDoseFindsAFutureStartDate() {
        val v = daily("08:00", start = "2026-11-10")
        assertEquals("2026-11-10T08:00", ScheduleEngine.nextDoseAfter(listOf(v), at("2026-11-02T12:00"), istanbul)!!.scheduledLocal.toString())
    }

    @Test
    fun everyOtherDayAndWeekdaysThroughTheEngine() {
        val everyOther = daily("08:00").copy(recurrence = Recurrence.EveryNDays(2, LocalDate.parse("2026-11-02")))
        assertEquals(
            listOf("2026-11-02T08:00", "2026-11-04T08:00", "2026-11-06T08:00"),
            ScheduleEngine.dosesBetween(listOf(everyOther), at("2026-11-01T00:00"), at("2026-11-06T23:59"), istanbul).locals(),
        )
        val monThu = daily("08:00").copy(recurrence = Recurrence.Weekdays(setOf(MONDAY, THURSDAY)))
        assertEquals(
            listOf("2026-11-02T08:00", "2026-11-05T08:00", "2026-11-09T08:00"),
            ScheduleEngine.dosesBetween(listOf(monThu), at("2026-11-01T00:00"), at("2026-11-09T23:59"), istanbul).locals(),
        )
    }
}