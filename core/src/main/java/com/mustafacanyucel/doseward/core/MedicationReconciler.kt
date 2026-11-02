package com.mustafacanyucel.doseward.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * What reconcile decided for one medication. The caller saves it in one transaction.
 */
data class MedicationReconcileResult(
    /** New DoseLog rows, with id = 0 until saved */
    val created: List<DoseLog>,
    /** Existing rows that changed. */
    val updated: List<DoseLog>,
    /** Doses marked missing in this run, oldest first. */
    val missed: List<DoseLog>,
    /** The open dose that is due now, if any, as it stands after this run. */
    val due: DoseLog?,
    /** True if [due] rang in this run, so the alarm or notification should show. */
    val rang: Boolean,
    /** When this medication next needs reconcile: a dose, a repeat, a snooze end, or a late window end. */
    val nextEventAt: Instant?,
    /** Save as Medication.dosesGeneratedUntil. */
    val dosesGeneratedUntil: Instant,
)

/**
 * Steps 1-3 of reconcile for one active medication, plus its part of step 6 (see the design doc).
 * Idempotent: running it again on its own saved result changes nothing.
 */
object MedicationReconciler {
    val RING_TOLERANCE: Duration = Duration.ofSeconds(60)

    fun reconcile(
        medication: Medication,
        versions: List<ScheduleVersion>,
        doses: List<DoseLog>,
        now: Instant,
        zone: ZoneId,
    ): MedicationReconcileResult {
        require(doses.none { it.id == 0L }) { "rows passed in must be saved rows with ids" }

        // Everything up to a minute from now counts as now, so doses that are seconds apart
        // (from one medication or several) are handled in one run and share one alarm screen.
        val horizon = now + RING_TOLERANCE

        // Step 1: rows for every scheduled time up to the horizon that doesn't have one yet.
        // Doses the user took or skipped early already have a row, so they are not created again
        val generateAfter = medication.dosesGeneratedUntil
            ?: versions.minOfOrNull { it.effectiveFrom }?.minusNanos(1)
            ?: now
        val existing  = doses.map { it.scheduleVersionId to it.scheduledLocal}.toSet()
        val created = ScheduleEngine.dosesBetween(versions, generateAfter, horizon, zone)
            .filter { (it.scheduleVersionId to it.scheduledLocal) !in existing}
            .map(::newRow)

        val rows = (doses + created).sortedBy { it.scheduledAt }.toMutableList()
        val latestPast = rows.indexOfLast { it.scheduledAt <= horizon }
        val missed = mutableListOf<DoseLog>()

        fun markMissed(i: Int) {
            rows[i] = rows[i].copy(status = DoseStatus.MISSED, nextRingAt = null, snoozedUntil = null)
            missed += rows[i]
        }

        // Step 2: an open dose with a later dose already due is superseded
        for (i in 0 until latestPast) {
            if (rows[i].status == DoseStatus.OPEN) markMissed(i)
        }

        // Steps 2-3: the latest past dose is due if it is open and still inside its late window.
        var closesAt: Instant? = null
        var rang = false
        if (latestPast >= 0 && rows[latestPast].status == DoseStatus.OPEN) {
            val dose = rows[latestPast]
            val nextDose = ScheduleEngine.nextDoseAfter(versions, dose.scheduledAt, zone)?.scheduledAt
            val closes = DoseRules.closesAt(medication, dose.scheduledAt, nextDose)
            if (closes <= now) {
                markMissed(latestPast)
            } else {
                closesAt = closes
                if (shouldRing(dose, horizon)) {
                    rows[latestPast] = ring(medication, dose, now, closes)
                    rang = true
                }
            }
        }
        val due = rows.getOrNull(latestPast)?.takeIf { it.status == DoseStatus.OPEN }

        // Step 6, this medication's part: the earliest moment reconcile must run again.
        val nextDose = ScheduleEngine.nextDoseAfter(versions, horizon, zone)?.scheduledAt
        val nextEventAt = listOfNotNull(nextDose, due?.nextRingAt, closesAt).minOrNull()

        val original = doses.associateBy { it.id }
        return MedicationReconcileResult(
            created = rows.filter { it.id == 0L },
            updated = rows.filter { it.id != 0L && it != original[it.id] },
            missed = missed,
            due = due,
            rang = rang,
            nextEventAt = nextEventAt,
            // Never moves back here; the clock-moved-back cleanup (stage 2.4) handles that case.
            dosesGeneratedUntil = maxOf(horizon, medication.dosesGeneratedUntil ?: horizon),
        )

    }

    private fun newRow(planned: PlannedDose) = DoseLog(
        id = 0,
        medicationId =  planned.medicationId,
        scheduleVersionId = planned.scheduleVersionId,
        scheduledLocal = planned.scheduledLocal,
        scheduledAt = planned.scheduledAt,
        status = DoseStatus.OPEN,
        amount = planned.amount,
        nextRingAt = planned.scheduledAt,
        createdBy = CreatedBy.APP,
    )

    private fun shouldRing(dose: DoseLog, horizon: Instant): Boolean {
        val ringAt = dose.nextRingAt ?: return false
        val snoozedUntil = dose.snoozedUntil
        val snoozed = snoozedUntil != null && snoozedUntil > horizon
        return !snoozed && ringAt <= horizon
    }

    /**
     * Records a ring and schedules the next repeat, if one is allowed before the dose closes.
     */
    private fun ring(medication: Medication, dose: DoseLog, now: Instant, closesAt: Instant): DoseLog {
        val ringCount = dose.ringCount + 1
        val nextRing = (now + medication.repeatInterval)
            .takeIf { ringCount < DoseRules.allowedRings(medication) && it < closesAt }
        return dose.copy(ringCount = ringCount, nextRingAt = nextRing, snoozedUntil = null)
    }
}
