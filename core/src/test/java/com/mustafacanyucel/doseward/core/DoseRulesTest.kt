package com.mustafacanyucel.doseward.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DoseRulesTest {

    private val med = Medication(id = 1, name = "Paxil", alertStyle = AlertStyle.ALARM_WITH_REPEATS)
    private val t0 = Instant.parse("2026-11-02T08:00:00Z")

    private fun hours(h: Long) = Duration.ofHours(h)
    private fun minutes(m: Long) = Duration.ofMinutes(m)

    @Test
    fun defaultLateWindowIsAThirdOfTheGapUpToSixHours() {
        assertEquals(hours(1), DoseRules.lateWindow(med, t0, t0 + hours(3)))
        assertEquals(minutes(160), DoseRules.lateWindow(med, t0, t0 + hours(8)))  // 2 h 40 min
        assertEquals(hours(6), DoseRules.lateWindow(med, t0, t0 + hours(24)))
        assertEquals(hours(6), DoseRules.lateWindow(med, t0, null))               // no next dose
    }

    @Test
    fun overrideReplacesTheDefault() {
        val custom = med.copy(lateWindowOverride = minutes(30))
        assertEquals(minutes(30), DoseRules.lateWindow(custom, t0, t0 + hours(24)))
    }

    @Test
    fun doseClosesAtTheNextDoseIfThatComesFirst() {
        // A 10 h override, but the next dose is 3 h away: supersession wins.
        val custom = med.copy(lateWindowOverride = hours(10))
        assertEquals(t0 + hours(3), DoseRules.closesAt(custom, t0, t0 + hours(3)))
        assertEquals(t0 + hours(1), DoseRules.closesAt(med, t0, t0 + hours(3)))
    }

    @Test
    fun snoozeChoicesEndBeforeTheDoseCloses() {
        val now = t0
        assertEquals(listOf(minutes(5), minutes(10)), DoseRules.snoozeChoices(now, now + minutes(15)))
        assertEquals(DoseRules.SNOOZE_CHOICES, DoseRules.snoozeChoices(now, now + hours(2)))
        assertEquals(emptyList<Duration>(), DoseRules.snoozeChoices(now, now + minutes(3)))
    }

    @Test
    fun onlyAlarmWithRepeatsRingsMoreThanOnce() {
        assertEquals(6, DoseRules.allowedRings(med))  // 1 ring + 5 repeats
        assertEquals(1, DoseRules.allowedRings(med.copy(alertStyle = AlertStyle.ALARM)))
        assertEquals(1, DoseRules.allowedRings(med.copy(alertStyle = AlertStyle.NOTIFICATION)))
    }
}