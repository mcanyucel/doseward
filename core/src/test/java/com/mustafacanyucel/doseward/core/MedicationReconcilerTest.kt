package com.mustafacanyucel.doseward.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationReconcilerTest {

    private val zone = ZoneId.of("Europe/Istanbul")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    /** 3x daily at 08:00, 14:00, 20:00, added Nov 2 at 07:00. Gap 6 h, so the late window is 2 h. */
    private val paxil = Medication(id = 1, name = "Paxil", alertStyle = AlertStyle.ALARM_WITH_REPEATS)
    private val threeTimesDaily = ScheduleVersion(
        id = 10,
        medicationId = 1,
        timesOfDay = listOf("08:00", "14:00", "20:00").map(LocalTime::parse),
        recurrence = Recurrence.EveryNDays(1, LocalDate.parse("2026-11-02")),
        dosePerIntake = Amount(1000),
        startDate = LocalDate.parse("2026-11-02"),
        effectiveFrom = at("2026-11-02T07:00"),
    )

    /** Stands in for the database: applies each result the way the app will. */
    private inner class Store(var medication: Medication = paxil) {
        val doses = mutableListOf<DoseLog>()
        private var nextId = 1L

        fun run(now: String): MedicationReconcileResult {
            val result = MedicationReconciler.reconcile(medication, listOf(threeTimesDaily), doses.toList(), at(now), zone)
            result.updated.forEach { changed -> doses[doses.indexOfFirst { it.id == changed.id }] = changed }
            result.created.forEach { doses += it.copy(id = nextId++) }
            medication = medication.copy(dosesGeneratedUntil = result.dosesGeneratedUntil)
            return result
        }

        fun add(row: DoseLog) {
            doses += row.copy(id = nextId++)
        }

        fun dose(local: String): DoseLog = doses.single { it.scheduledLocal == LocalDateTime.parse(local) }
    }

    @Test
    fun firstDoseIsCreatedAndRings() {
        val store = Store()
        val result = store.run("2026-11-02T08:00:10")
        assertEquals(1, result.created.size)
        assertTrue(result.rang)
        val dose = store.dose("2026-11-02T08:00")
        assertEquals(DoseStatus.OPEN, dose.status)
        assertEquals(1, dose.ringCount)
        assertEquals(at("2026-11-02T08:05:10"), dose.nextRingAt)  // first repeat in 5 min
        assertEquals(at("2026-11-02T08:05:10"), result.nextEventAt)
    }

    @Test
    fun runningTwiceChangesNothing() {
        val store = Store()
        store.run("2026-11-02T08:00:10")
        val again = store.run("2026-11-02T08:00:10")
        assertTrue(again.created.isEmpty())
        assertTrue(again.updated.isEmpty())
        assertFalse(again.rang)
        assertEquals(1, store.dose("2026-11-02T08:00").ringCount)
    }

    @Test
    fun repeatsStopAfterFiveAndTheDoseClosesAtItsWindowEnd() {
        val store = Store()
        var next = "2026-11-02T08:00"
        repeat(6) {
            val result = store.run(next)
            assertTrue(result.rang)
            next = LocalDateTime.ofInstant(result.nextEventAt, zone).toString()
        }
        assertEquals(6, store.dose("2026-11-02T08:00").ringCount)
        assertNull(store.dose("2026-11-02T08:00").nextRingAt)
        assertEquals("2026-11-02T10:00", next)  // late window end: 08:00 + 2 h

        val closing = store.run(next)
        assertEquals(DoseStatus.MISSED, store.dose("2026-11-02T08:00").status)
        assertEquals(1, closing.missed.size)
        assertNull(closing.due)
        assertEquals(at("2026-11-02T14:00"), closing.nextEventAt)
    }

    @Test
    fun doseWithinAMinuteCountsAsDueNow() {
        val store = Store()
        val early = store.run("2026-11-02T07:59:30")  // 30 s before 08:00
        assertTrue(early.rang)
        assertEquals(1, early.created.size)

        val tooEarly = Store().run("2026-11-02T07:58:30")  // 90 s before: not yet
        assertFalse(tooEarly.rang)
        assertEquals(at("2026-11-02T08:00"), tooEarly.nextEventAt)
    }

    @Test
    fun nextDoseSupersedesAnOpenDose() {
        // A 10 h window keeps 08:00 open past 14:00, but the 14:00 dose closes it.
        val store = Store(paxil.copy(lateWindowOverride = Duration.ofHours(10)))
        store.run("2026-11-02T08:00")
        val result = store.run("2026-11-02T14:00:05")
        assertEquals(DoseStatus.MISSED, store.dose("2026-11-02T08:00").status)
        assertEquals(listOf(LocalDateTime.parse("2026-11-02T08:00")), result.missed.map { it.scheduledLocal })
        assertTrue(result.rang)
        assertEquals(LocalDateTime.parse("2026-11-02T14:00"), result.due!!.scheduledLocal)
    }

    @Test
    fun outageCatchUpMarksEarlierDosesMissedAndRingsTheLatest() {
        val store = Store()
        store.run("2026-11-02T08:00")
        store.doses[0] = store.doses[0].copy(status = DoseStatus.TAKEN, nextRingAt = null)

        // Phone off from Nov 2 08:00 to Nov 4 14:30.
        val result = store.run("2026-11-04T14:30")
        assertEquals(7, result.created.size)   // Nov 2 14,20 / Nov 3 8,14,20 / Nov 4 8,14
        assertEquals(6, result.missed.size)    // "6 earlier doses were marked missed"
        assertTrue(result.rang)
        assertEquals(LocalDateTime.parse("2026-11-04T14:00"), result.due!!.scheduledLocal)  // rings "30 min late"
    }

    @Test
    fun afterAnOutagePastTheWindowNothingRings() {
        val store = Store()
        store.run("2026-11-02T08:00")
        store.doses[0] = store.doses[0].copy(status = DoseStatus.TAKEN, nextRingAt = null)

        val result = store.run("2026-11-04T16:30")  // Nov 4 14:00 closed at 16:00
        assertEquals(7, result.missed.size)
        assertFalse(result.rang)
        assertNull(result.due)
        assertEquals(at("2026-11-04T20:00"), result.nextEventAt)
    }

    @Test
    fun snoozedDoseWaitsForTheSnoozeToEnd() {
        val store = Store()
        store.run("2026-11-02T08:00")
        // The user snoozes 20 min. (The snooze action itself comes in build step 3.)
        val snoozeEnd = at("2026-11-02T08:20")
        store.doses[0] = store.doses[0].copy(snoozedUntil = snoozeEnd, nextRingAt = snoozeEnd, ringCount = 0)

        val during = store.run("2026-11-02T08:10")
        assertFalse(during.rang)
        assertEquals(snoozeEnd, during.nextEventAt)

        val after = store.run("2026-11-02T08:20")
        assertTrue(after.rang)
        assertNull(store.dose("2026-11-02T08:00").snoozedUntil)
    }

    @Test
    fun earlyTakenDoseIsNotCreatedAgainOrRung() {
        val store = Store()
        store.run("2026-11-02T08:00")
        store.doses[0] = store.doses[0].copy(status = DoseStatus.TAKEN, nextRingAt = null)
        // At 12:00 the user takes the 14:00 dose early.
        store.add(
            DoseLog(
                id = 0, medicationId = 1, scheduleVersionId = 10,
                scheduledLocal = LocalDateTime.parse("2026-11-02T14:00"), scheduledAt = at("2026-11-02T14:00"),
                status = DoseStatus.TAKEN, actedAt = at("2026-11-02T12:00"), amount = Amount(1000),
                createdBy = CreatedBy.USER,
            )
        )

        val result = store.run("2026-11-02T14:00:05")
        assertTrue(result.created.isEmpty())
        assertFalse(result.rang)
        assertNull(result.due)
        assertEquals(2, store.doses.size)
    }

    @Test
    fun notificationStyleRingsOnce() {
        val store = Store(paxil.copy(alertStyle = AlertStyle.NOTIFICATION))
        val result = store.run("2026-11-02T08:00")
        assertTrue(result.rang)
        assertNull(store.dose("2026-11-02T08:00").nextRingAt)
        assertEquals(at("2026-11-02T10:00"), result.nextEventAt)  // only the window end is left
    }
}