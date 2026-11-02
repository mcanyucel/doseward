package com.mustafacanyucel.doseward.core

import java.time.Instant
import java.time.LocalDateTime

/** One scheduled dose and what happened to it. */
data class DoseLog(
    val id: Long,
    val medicationId: Long,
    val scheduleVersionId: Long,
    /** The wall-clock time the schedule asked for, e.g. 08:00 on Nov 2. */
    val scheduledLocal: LocalDateTime,
    /** That time as an instant, in the time zone at the moment the row was created. */
    val scheduledAt: Instant,
    val status: DoseStatus,
    val actedAt: Instant? = null,
    val amount: Amount,
    val snoozedUntil: Instant? = null,
    val nextRingAt: Instant? = null,
    val ringCount: Int = 0,
    val createdBy: CreatedBy,
)