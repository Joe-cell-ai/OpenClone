package org.openclone.app.clone

import java.io.File
import java.util.zip.ZipFile

/**
 * Estimates the heap needed by resource-table rewriting before a build deletes or creates output.
 * APK entry copying is streamed; the resource table is the largest input that the rebuilder parses.
 */
data class CloneBuildPreflightReport(
    val sourceBytes: Long,
    val resourceTableBytes: Long,
    val dexBytes: Long,
    val estimatedPeakBytes: Long,
    val availableHeapBytes: Long,
    val canProceed: Boolean
)

object CloneBuildPreflight {
    private const val BASE_OVERHEAD_BYTES = 32L * 1024L * 1024L
    private const val MINIMUM_HEADROOM_BYTES = 64L * 1024L * 1024L
    private const val RESOURCE_TABLE_MULTIPLIER = 3L
    private const val DEX_MULTIPLIER = 4L

    fun inspect(
        sourceApks: List<File>,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
        allocatedHeapBytes: Long = Runtime.getRuntime().totalMemory(),
        freeAllocatedHeapBytes: Long = Runtime.getRuntime().freeMemory()
    ): CloneBuildPreflightReport {
        require(sourceApks.isNotEmpty()) { "No APK files were supplied." }
        require(sourceApks.all { it.isFile && it.canRead() }) {
            "One or more source APK files cannot be read."
        }
        val sourceBytes = sourceApks.sumOf { it.length() }
        val resourceTableBytes = sourceApks.sumOf { apk ->
            ZipFile(apk).use { zip -> zip.getEntry("resources.arsc")?.size?.coerceAtLeast(0L) ?: 0L }
        }
        val dexBytes = sourceApks.sumOf { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("""classes(\d*)\.dex""")) }
                    .sumOf { it.size.coerceAtLeast(0L) }
            }
        }
        val estimated = maxOf(
            MINIMUM_HEADROOM_BYTES,
            BASE_OVERHEAD_BYTES + resourceTableBytes * RESOURCE_TABLE_MULTIPLIER + dexBytes * DEX_MULTIPLIER
        )
        val usedHeap = (allocatedHeapBytes - freeAllocatedHeapBytes).coerceAtLeast(0L)
        val available = (maxHeapBytes - usedHeap).coerceAtLeast(0L)
        return CloneBuildPreflightReport(
            sourceBytes = sourceBytes,
            resourceTableBytes = resourceTableBytes,
            dexBytes = dexBytes,
            estimatedPeakBytes = estimated,
            availableHeapBytes = available,
            canProceed = available >= estimated
        )
    }

    fun requireSafe(report: CloneBuildPreflightReport) {
        check(report.canProceed) {
            "Not enough free memory to safely rebuild this APK set. Estimated need: " +
                "${report.estimatedPeakBytes / (1024 * 1024)} MB; available heap: " +
                "${report.availableHeapBytes / (1024 * 1024)} MB. Close other apps and try again."
        }
    }
}
