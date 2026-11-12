package org.openclone.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneUpdateStatusTest {
    @Test
    fun reportsOnlyStrictlyNewerInstalledSourceVersions() {
        assertTrue(CloneUpdateStatus.isUpdateAvailable(10L, 11L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, 10L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, 9L))
        assertFalse(CloneUpdateStatus.isUpdateAvailable(10L, null))
    }
}
