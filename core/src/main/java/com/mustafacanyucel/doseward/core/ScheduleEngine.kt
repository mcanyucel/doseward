package com.mustafacanyucel.doseward.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Turns one medication's schedule versions into concrete dose times.
 * Pure: the caller passes the time zone, so tests can use any zone.
 */
object ScheduleEngine {

    /**
     * Every dose strictly after [after], in time order. Lazy: it ends when all versions have ended,
     * and otherwise runs forever, so take only what you need.
     */
    fun dosesAfter(versions: List<ScheduleVersion>, after: Instant, zone: ZoneId): Sequence<PlannedDose> =
        sequence {
            if (versions.isEmpty()) return@sequence
            val lastDate = lastPossibleDate(versions, zone)
            // Start a day early: a DST shift can move a dose's instant across midnight.
            var date = after.atZone(zone).toLocalDate().minusDays(1)
            while (lastDate == null || date <= lastDate) {
                val day = versions
                    .flatMap { dosesOn(it, date, zone) }
                    .filter { it.scheduledAt > after }
                    .sortedWith(compareBy({ it.scheduledAt }, { it.scheduledLocal }))
                yieldAll(day)
                date = date.plusDays(1)
            }
        }

    /**
     * Doses in (after, until]: after is excluded, until is included.
     */
    fun dosesBetween(versions: List<ScheduleVersion>, after: Instant, until: Instant, zone: ZoneId): List<PlannedDose> =
        dosesAfter(versions, after, zone).takeWhile { it.scheduledAt <= until }.toList()

    /**
     * The first dose strictly after [after], or null if the schedule has ended
     */
    fun nextDoseAfter(versions:List<ScheduleVersion>, after: Instant, zone: ZoneId): PlannedDose? =
        dosesAfter(versions, after, zone).firstOrNull()



    private fun dosesOn(version: ScheduleVersion, date: LocalDate, zone: ZoneId): List<PlannedDose> {
        if (date < version.startDate) return emptyList()
        if (version.endDate != null && date > version.endDate) return emptyList()
        if (!version.recurrence.occursOn(date)) return emptyList()

        return version.timesOfDay
            .map { time ->
                val local = LocalDateTime.of(date, time)
                // java.time resolves DST: a time in the spring gap moves  forward
                // by the gap's length, and a repeated autumn times takes its first occurrence.
                val at = ZonedDateTime.of(local, zone).toInstant()
                PlannedDose(version.medicationId, version.id, local, at, version.dosePerIntake)
            }
            .filter { it.scheduledAt >= version.effectiveFrom }
            .filter { version.effectiveUntil == null || it.scheduledAt < version.effectiveUntil }
    }

    /**
     * The last date any version can have a dose on, or null if one runs forever
     */
    private fun lastPossibleDate(versions: List<ScheduleVersion>, zone: ZoneId): LocalDate? =
        versions
            .map { version ->
                val untilDate = version.effectiveUntil?.atZone(zone)?.toLocalDate()?.plusDays(1)
                listOfNotNull(version.endDate, untilDate).minOrNull() ?: return null
            }
            .max()

}