package com.mustafacanyucel.doseward.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountTest {

    @Test
    fun parsesDecimalsAndCommas() {
        assertEquals(Amount(1500), Amount.parse("1.5"))
        assertEquals(Amount(1500), Amount.parse(" 1,5 "))
        assertEquals(Amount(250), Amount.parse("0.25"))
        assertEquals(Amount(2000), Amount.parse("2"))
    }

    @Test
    fun rejectsNonNumbersAndTooManyDecimals() {
        assertNull(Amount.parse("abc"))
        assertNull(Amount.parse(""))
        assertNull(Amount.parse("0.0001"))
    }

    @Test
    fun formatsWithoutTrailingZeros() {
        assertEquals("1.5", Amount(1500).toString())
        assertEquals("0.25", Amount(250).toString())
        assertEquals("2", Amount(2000).toString())
        assertEquals("10", Amount(10_000).toString())
        assertEquals("0", Amount.ZERO.toString())
    }

    @Test
    fun sumsAreExact() {
        // 0.1 + 0.2 is not 0.3 in floating point; in milli-units it is.
        assertEquals(Amount.parse("0.3"), Amount.parse("0.1")!! + Amount.parse("0.2")!!)
        assertEquals(Amount(4500), Amount(1500) * 3)
    }

    @Test
    fun quarterSteps() {
        assertTrue(Amount(1750).isQuarterStep)
        assertFalse(Amount(1600).isQuarterStep)
    }
}