package org.openclone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenCaptureBoundsTest {
    @Test fun keepsSmallDisplaysAtNativeSize() {
        assertEquals(1080 to 1800, ScreenCaptureBounds.fit(1080, 1800))
    }
    @Test fun scalesLargeDisplaysAndPreservesAspectRatio() {
        assertEquals(960 to 1920, ScreenCaptureBounds.fit(1440, 2880))
    }
    @Test fun rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) { ScreenCaptureBounds.fit(0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { ScreenCaptureBounds.fit(1080, -1) }
    }
    @Test fun rejectsUnsafeCaptureLimits() {
        assertThrows(IllegalArgumentException::class.java) { ScreenCaptureBounds.fit(1080, 1920, 100) }
    }
}
