package org.openclone.app.clone

import java.io.File
import java.util.zip.ZipFile

/**
 * Estimates the heap needed by resource-table rewriting before a build deletes or creates output.
 * APK entry copying is streamed; DEX rewriting currently uses dexlib2 and therefore needs a
 * conservative allowance for its in-memory model.
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
        maxHeapBytes: Long? = null,
        allocatedHeapBytes: Long? = null,
        freeAllocatedHeapBytes: Long? = null,
        rewriteDexPackageStrings: Boolean = false,
        ensureActive: () -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): CloneBuildPreflightReport {
        require(sourceApks.isNotEmpty()) { "No APK files were supplied." }
        require(sourceApks.all { it.isFile && it.canRead() }) {
            "One or more source APK files cannot be read."
        }

        // Do GC before sampling the runtime. Default arguments would otherwise capture total/free
        // heap before collection and make the preflight report stale even after GC succeeds.
        if (maxHeapBytes == null || allocatedHeapBytes == null || freeAllocatedHeapBytes == null) {
            Runtime.getRuntime().gc()
        }
        val runtime = Runtime.getRuntime()
        val measuredMaxHeap = maxHeapBytes ?: runtime.maxMemory()
        val measuredAllocatedHeap = allocatedHeapBytes ?: runtime.totalMemory()
        val measuredFreeAllocatedHeap = freeAllocatedHeapBytes ?: runtime.freeMemory()

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
            BASE_OVERHEAD_BYTES + resourceTableBytes * RESOURCE_TABLE_MULTIPLIER +
                if (rewriteDexPackageStrings) dexBytes * DEX_MULTIPLIER else 0L
        )
        val usedHeap = (measuredAllocatedHeap - measuredFreeAllocatedHeap).coerceAtLeast(0L)
        val available = (measuredMaxHeap - usedHeap).coerceAtLeast(0L)
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
