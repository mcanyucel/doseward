package com.mustafacanyucel.doseward.core

import java.time.Duration
import java.time.Instant

data class Medication(
    val id: Long,
    val name: String,
    /** Shown instead of the name on the lock screen, if set. */
    val alias: String? = null,
    val unitLabel: String = "tablet",
    /** Display only, e.g. "20 mg". Stock is always counted in units. */
    val strength: String? = null,
    val stock: Amount = Amount.ZERO,
    val alertStyle: AlertStyle,
    val repeatInterval: Duration = Duration.ofMinutes(5),
    val maxRepeats: Int = 5,
    /** Null means the default rule (see Dose semantics in docs) */
    val lateWindowOverride: Duration? = null,
    val lowStockDays: Int = 5,
    val lowStockWarned: Boolean = false,
    /** Dose rows exist for every scheduled time up to here. Null = none generated yet. */
    val dosesGeneratedUntil: Instant? = null,
    val active: Boolean = true
) {
    val lockScreenName: String get() = alias ?: name
}
