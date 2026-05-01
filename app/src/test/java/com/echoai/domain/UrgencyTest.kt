package com.echoai.domain

import org.junit.Assert.*
import org.junit.Test

class UrgencyTest {

    @Test
    fun ordinalRanksAreStrictlyOrdered() {
        assertTrue(Urgency.CRITICAL.ordinalRank > Urgency.HIGH.ordinalRank)
        assertTrue(Urgency.HIGH.ordinalRank > Urgency.MEDIUM.ordinalRank)
        assertTrue(Urgency.MEDIUM.ordinalRank > Urgency.LOW.ordinalRank)
    }

    @Test
    fun tintBackgroundAlphaIs0x24ForAllLevels() {
        for (u in Urgency.entries) {
            val alpha = (u.tintBackground ushr 24) and 0xFF
            assertEquals("${u.name} tintBackground alpha should be 0x24", 0x24, alpha)
        }
    }

    @Test
    fun tintBackgroundRgbMatchesColorRgb() {
        for (u in Urgency.entries) {
            val colorRgb = u.color and 0x00FFFFFF
            val tintRgb = u.tintBackground and 0x00FFFFFF
            assertEquals("${u.name} tintBackground RGB should match color RGB", colorRgb, tintRgb)
        }
    }

    @Test
    fun textColorDiffersFromMainColor() {
        for (u in Urgency.entries) {
            assertNotEquals("${u.name} textColor should differ from color", u.color, u.textColor)
        }
    }

    @Test
    fun allFourLevelsArePresent() {
        val levels = Urgency.entries
        assertEquals(4, levels.size)
        assertTrue(levels.contains(Urgency.CRITICAL))
        assertTrue(levels.contains(Urgency.HIGH))
        assertTrue(levels.contains(Urgency.MEDIUM))
        assertTrue(levels.contains(Urgency.LOW))
    }

    @Test
    fun lowHasLowestRank() {
        for (u in Urgency.entries) {
            if (u != Urgency.LOW) {
                assertTrue("${u.name} rank should exceed LOW", u.ordinalRank > Urgency.LOW.ordinalRank)
            }
        }
    }
}
