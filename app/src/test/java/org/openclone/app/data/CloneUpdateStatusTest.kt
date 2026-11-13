package org.openclone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneUpdateStatusTest {
    @Test
    fun reportsOnlyStrictlyNewerKnownInstalledSourceVersions() {
        assertTrue(CloneUpdateStatus.isUpdateAvailable(10L, 11L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, 10L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, 9L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, null))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(0L, 11L))
    }

    @Test
    fun explainsUnavailableAndUnknownVersionStates() {
        assertEquals("Version unknown", CloneUpdateStatus.label(0L, 11L))
        assertEquals("Original app unavailable", CloneUpdateStatus.label(10L, null))
        assertEquals("Update available", CloneUpdateStatus.label(10L, 11L))
        assertEquals("Original app older", CloneUpdateStatus.label(10L, 9L))
        assertEquals("Up to date", CloneUpdateStatus.label(10L, 10L))
    }
}
