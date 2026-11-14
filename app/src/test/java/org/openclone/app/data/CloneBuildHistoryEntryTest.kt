package org.openclone.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneBuildHistoryEntryTest {
    @Test
    fun filesAvailableRequiresEveryApkAndObbFile() {
        val apk = File.createTempFile("openclone-history", ".apk")
        val obb = File.createTempFile("openclone-history", ".obb")
        try {
            val entry = CloneBuildHistoryEntry(
                originalPackage = "com.example.original",
                clonePackage = "com.example.original.clone",
                cloneLabel = "Example Clone",
                apkPaths = listOf(apk.absolutePath),
                obbPaths = listOf(obb.absolutePath),
                iconBadgeApplied = true,
                customIconApplied = false,
                createdAtMillis = 1234L
            )
            assertTrue(entry.filesAvailable)
            assertEquals("com.example.original.clone", entry.toBuildResult().clonePackage)
            assertEquals(1, entry.toBuildResult().apks.size)
            assertEquals(1, entry.toBuildResult().obbFiles.size)
            assertTrue(obb.delete())
            assertFalse(entry.filesAvailable)
        } finally {
            apk.delete()
            obb.delete()
        }
    }

    @Test
    fun emptyApkSetIsNotInstallable() {
        val entry = CloneBuildHistoryEntry(
            originalPackage = "com.example.original",
            clonePackage = "com.example.clone",
            cloneLabel = "Clone",
            apkPaths = emptyList(),
            obbPaths = emptyList(),
            iconBadgeApplied = false,
            customIconApplied = false,
            createdAtMillis = 1L
        )
        assertFalse(entry.filesAvailable)
    }
}
