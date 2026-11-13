package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloneBuildPreflightTest {
    @Test
    fun reportsSourceAndResourceTableSizesAndAvailableHeap() {
        val apk = createApk(resourceBytes = 1024L)
        try {
            val report = CloneBuildPreflight.inspect(
                listOf(apk),
                maxHeapBytes = 256L * 1024 * 1024,
                allocatedHeapBytes = 64L * 1024 * 1024,
                freeAllocatedHeapBytes = 32L * 1024 * 1024
            )
            assertEquals(apk.length(), report.sourceBytes)
            assertEquals(1024L, report.resourceTableBytes)
            assertEquals(2048L, report.dexBytes)
            assertEquals(64L * 1024 * 1024, report.estimatedPeakBytes)
            val rewriteReport = CloneBuildPreflight.inspect(
                listOf(apk),
                maxHeapBytes = 256L * 1024 * 1024,
                allocatedHeapBytes = 64L * 1024 * 1024,
                freeAllocatedHeapBytes = 32L * 1024 * 1024,
                rewriteDexPackageStrings = true
            )
            assertEquals(report.estimatedPeakBytes + 2048L * 4L, rewriteReport.estimatedPeakBytes)
            assertEquals(224L * 1024 * 1024, report.availableHeapBytes)
            assertTrue(report.canProceed)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun rejectsBuildWhenAvailableHeapIsBelowSafetyEstimate() {
        val apk = createApk(resourceBytes = 1024L)
        try {
            val report = CloneBuildPreflight.inspect(
                listOf(apk),
                maxHeapBytes = 128L * 1024 * 1024,
                allocatedHeapBytes = 112L * 1024 * 1024,
                freeAllocatedHeapBytes = 8L * 1024 * 1024
            )
            assertFalse(report.canProceed)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun rejectsMissingSourceFiles() {
        val missing = File.createTempFile("missing-apk", ".apk").apply { delete() }
        try {
            CloneBuildPreflight.inspect(listOf(missing))
            throw AssertionError("Expected missing APK to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun createApk(resourceBytes: Long): File {
        val file = File.createTempFile("preflight", ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(ByteArray(resourceBytes.toInt()))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(2048))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        return file
    }
}
