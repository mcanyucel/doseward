package com.mustafacanyucel.doseward.core

import java.time.Duration
import java.time.Instant

/**
 * The rules from "Dose semantics" in the design doc, as small pure functions.
 */
object DoseRules {
    val MAX_LATE_WINDOW: Duration = Duration.ofHours(6)

    /**
     * Snooze lengths the alarm can offer, before capping.
     */
    val SNOOZE_CHOICES: List<Duration> = listOf(5L, 10L, 15L, 30L, 60L).map(Duration::ofMinutes)

    /**
     * How long after its scheduled time a dose can still be taken from the alarm:
     * the medication's override if set, otherwise a third of the gap to the next dose, at most 6h.
     */
    fun lateWindow(medication: Medication, scheduledAt: Instant, nextDoseAt: Instant?) : Duration {
        medication.lateWindowOverride?.let { return it }
        if (nextDoseAt == null) return MAX_LATE_WINDOW
        return minOf(Duration.between(scheduledAt, nextDoseAt).dividedBy(3), MAX_LATE_WINDOW)
    }

    /**
     * When an open dose becomes missed: its late window ends or the next dose arrives, whichever
     * is first.
     */
    fun closesAt(medication: Medication, scheduledAt: Instant, nextDoseAt: Instant?): Instant {
        val windowEnd = scheduledAt + lateWindow(medication, scheduledAt, nextDoseAt)
        return if (nextDoseAt != null && nextDoseAt < windowEnd) nextDoseAt else windowEnd
    }

    /**
     * The snooze choices that end before the dose closes.
     */
    fun snoozeChoices(now: Instant, closesAt: Instant): List<Duration> =
        SNOOZE_CHOICES.filter { now + it < closesAt }

    /**
     * How many times a dose rings in total, counting the first ring.
     */
    fun allowedRings(medication: Medication): Int =
        if (medication.alertStyle == AlertStyle.ALARM_WITH_REPEATS) 1 + medication.maxRepeats else 1

}