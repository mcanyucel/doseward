package com.mustafacanyucel.doseward.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A quantity of medication units (tablets, capsules, ...) stored in thousandths:
 * 1.5 tablets is Amount(1500). Integer math keeps sums exact.
 */
@JvmInline
value class Amount(val milli: Long) : Comparable<Amount> {
    operator fun plus(other: Amount) = Amount(milli + other.milli)
    operator fun minus(other: Amount) = Amount(milli - other.milli)
    operator fun times(count: Int) = Amount(milli * count)
    override fun compareTo(other: Amount) = milli.compareTo(other.milli)

    /** The steps the dose picker offers. */
    val isQuarterStep: Boolean get() = milli % QUARTER ==0L

    /** "1.5", "2", "0.25": no trailing zeros. */
    override fun toString():String =
        BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString()

    companion object {
        const val QUARTER = 250L
        val ZERO = Amount(0)

        fun units(whole: Long) = Amount(whole * 1000)

        fun parse(text: String): Amount? {
            val number = text.trim().replace(',', '.').toBigDecimalOrNull()
                ?: return null

            return try {
                Amount(number.movePointRight(3)
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact())
            } catch (e: ArithmeticException) {
                null
            }
        }

    }
}