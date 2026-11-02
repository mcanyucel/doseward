package com.mustafacanyucel.doseward.core

import java.time.Instant
import java.time.LocalDateTime

/** A dose the schedule calls for. Not stored: reconcile turns these into DoseLog rows. */
data class PlannedDose(
    val medicationId: Long,
    val scheduleVersionId: Long,
    /** The wall-clock time from the schedule, e.g. 2026-11-02T08:00. */
    val scheduledLocal: LocalDateTime,
    /** When that is, in the time zone the engine was given. */
    val scheduledAt: Instant,
    val amount: Amount,
)