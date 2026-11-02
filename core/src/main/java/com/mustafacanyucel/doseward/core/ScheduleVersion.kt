package com.mustafacanyucel.doseward.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * One version of a medication's schedule. Editing a schedule closes the current
 * version (sets effectiveUntil) and starts a new one, so past doses keep their rule.
 */
data class ScheduleVersion(
    val id: Long,
    val medicationId: Long,
    val timesOfDay: List<LocalTime>,
    val recurrence: Recurrence,
    val dosePerIntake: Amount,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant? = null,
) {
    init {
        require(timesOfDay.isNotEmpty()) { "at least one time of day" }
        require(timesOfDay == timesOfDay.sorted().distinct()) { "times must be sorted, no repeats" }
        require(dosePerIntake > Amount.ZERO && dosePerIntake.isQuarterStep) { "dose must be a positive quarter step" }
        require(endDate == null || endDate >= startDate) { "end date before start date" }
        require(effectiveUntil == null || effectiveUntil > effectiveFrom) { "empty effective range" }
    }
}
