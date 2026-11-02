package com.mustafacanyucel.doseward.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which days a schedule has doses on. */
sealed interface Recurrence {
    fun occursOn(date: LocalDate) : Boolean

    /** Length of one repeating cycle in days */
    val cycleDays: Int

    /**
     * Dose days in one cycle. With [cycleDays] this gives the daily rate for stock.
     */
    val doseDaysPerCycle: Int

    /**
     * Every N days counted from an anchor date: N = 1 is daily, N = 2 every other day.
     */
    data class EveryNDays(val n: Int, val anchor: LocalDate): Recurrence {
        init {
            require (n >= 1) { "n must be at least 1, was $n" }
        }

        override fun occursOn(date: LocalDate): Boolean =
            Math.floorMod(ChronoUnit.DAYS.between(anchor, date), n.toLong()) == 0L

        override val cycleDays get() = n;
        override val doseDaysPerCycle: Int
            get() = 1
    }

    /** Chosen days of the week, e.g. Monday and Thursday. */
    data class Weekdays(val days: Set<DayOfWeek>) : Recurrence {
        init {
            require(days.isNotEmpty()) { "pick at least one weekday" }
        }

        override fun occursOn(date: LocalDate): Boolean = date.dayOfWeek in days

        override val cycleDays get() = 7
        override val doseDaysPerCycle get() = days.size
    }
}