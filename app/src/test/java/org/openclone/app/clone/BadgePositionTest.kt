package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgePositionTest {
    @Test
    fun positionsCoverAllFourCorners() {
        assertEquals(0.24f, BadgePosition.TOP_LEFT.centerX, 0.0001f)
        assertEquals(0.24f, BadgePosition.TOP_LEFT.centerY, 0.0001f)
        assertEquals(0.76f, BadgePosition.TOP_RIGHT.centerX, 0.0001f)
        assertEquals(0.24f, BadgePosition.TOP_RIGHT.centerY, 0.0001f)
        assertEquals(0.24f, BadgePosition.BOTTOM_LEFT.centerX, 0.0001f)
        assertEquals(0.76f, BadgePosition.BOTTOM_LEFT.centerY, 0.0001f)
        assertEquals(0.76f, BadgePosition.BOTTOM_RIGHT.centerX, 0.0001f)
        assertEquals(0.76f, BadgePosition.BOTTOM_RIGHT.centerY, 0.0001f)
    }

    @Test
    fun everyPositionStaysInsideTheVisibleIconBounds() {
        BadgePosition.values().forEach { position ->
            assert(position.centerX in 0.0f..1.0f)
            assert(position.centerY in 0.0f..1.0f)
        }
    }
}
