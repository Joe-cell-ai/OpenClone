package org.openclone.app.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class ExternalApkSet(
    val archiveName: String,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val apkFiles: List<File>,
    val obbFiles: List<File> = emptyList()
)

/** Imports an APK or split-APK archive without trusting archive entry paths. */
class ExternalApkImporter(private val context: Context) {
    fun import(uri: Uri): ExternalApkSet {
        val archiveName = displayName(uri)
        val outputDir = File(context.cacheDir, "openclone/imports/${System.currentTimeMillis()}-${archiveName.hashCode().toUInt()}")
        check(outputDir.mkdirs()) { "Could not create a temporary import directory." }

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("The selected file could not be opened.")
            val candidates = mutableListOf<Pair<String, File>>()
            val obbFiles = mutableListOf<File>()
            input.use { stream ->
                if (archiveName.lowercase(Locale.ROOT).endsWith(".apk")) {
                    val apk = File(outputDir, "part-0.apk")
                    FileOutputStream(apk).use { output -> stream.copyBoundedTo(output, MAX_TOTAL_BYTES) }
                    candidates += archiveName to apk
                } else {
                    ZipInputStream(stream).use { zip ->
                        var entryCount = 0
                        var totalBytes = 0L
                        var entry = zip.nextEntry
                        while (entry != null) {
                            entryCount++
                            require(entryCount <= MAX_ENTRIES) { "The archive contains too many entries." }
                            if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")) {
                                val file = File(outputDir, "part-${candidates.size}.apk")
                                val copied = FileOutputStream(file).use { zip.copyBoundedTo(it, MAX_TOTAL_BYTES - totalBytes) }
                                totalBytes += copied
                                if (copied > 0L) candidates += entry.name to file else file.delete()
                            } else if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".obb")) {
                                val originalName = entry.name.substringAfterLast('/').takeIf { it.isNotBlank() }
                                    ?: "expansion-${obbFiles.size}.obb"
                                val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
                                val file = File(outputDir, safeName)
                                val copied = FileOutputStream(file).use { zip.copyBoundedTo(it, MAX_TOTAL_BYTES - totalBytes) }
                                totalBytes += copied
                                if (copied > 0L) obbFiles += file else file.delete()
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }

            require(candidates.isNotEmpty()) {
                "No APK files were found. Select an .apk, .apks, .xapk, or .apkm file."
            }
            val inspected = candidates.map { (name, file) -> Candidate(name, file, packageInfo(file)) }
            val baseCandidates = inspected.filter { it.info.splitNames.isNullOrEmpty() }
            require(baseCandidates.isNotEmpty()) {
                "This APK set has no base APK. Select a complete bundle containing a base APK."
            }
            val base = baseCandidates.firstOrNull { it.entryName.substringAfterLast('/').equals("base.apk", true) }
                ?: baseCandidates.first()
            val packageName = base.info.packageName
            val versionCode = base.info.versionCodeCompat()
            val matching = inspected.filter {
                it.info.packageName == packageName && it.info.versionCodeCompat() == versionCode
            }
            require(matching.size == inspected.size) {
                "The archive contains APKs from different apps or versions. Import one complete app bundle at a time."
            }
            val splitNames = matching.flatMap { it.info.splitNames?.toList().orEmpty() }
            require(splitNames.size == splitNames.distinct().size) { "The APK set contains duplicate split components." }

            val splitFiles = matching.filterNot { it === base }
                .sortedBy { it.info.splitNames?.firstOrNull().orEmpty() }
                .map { it.file }
            val baseInfo = base.info
            val appInfo = baseInfo.applicationInfo ?: error("The base APK has no application metadata.")
            appInfo.sourceDir = base.file.absolutePath
            appInfo.publicSourceDir = base.file.absolutePath
            val label = runCatching { appInfo.loadLabel(context.packageManager).toString() }.getOrDefault(packageName)
            return ExternalApkSet(
                archiveName, label, packageName, baseInfo.versionName,
                versionCode, listOf(base.file) + splitFiles, obbFiles
            )
        } catch (error: Exception) {
            outputDir.deleteRecursively()
            throw error
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "selected.apk"

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: error("${file.name} is not a valid Android APK.")
    }

    private data class Candidate(val entryName: String, val file: File, val info: PackageInfo)

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream, remainingLimit: Long): Long {
        require(remainingLimit > 0) { "The selected APK data exceeds the import size limit." }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= remainingLimit) { "The selected APK data exceeds the import size limit." }
            output.write(buffer, 0, count)
        }
        return total
    }

    companion object {
        private const val MAX_ENTRIES = 512
        private const val MAX_TOTAL_BYTES = 768L * 1024L * 1024L
    }
}
