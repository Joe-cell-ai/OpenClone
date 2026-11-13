package org.openclone.app.clone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs APK rebuilding in WorkManager so progress and cancellation survive Activity recreation. */
class CloneBuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @Suppress("DEPRECATION")
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            val sourcePaths = inputData.getStringArray(KEY_SOURCE_APKS)
                ?: error("No source APK paths were supplied.")
            val sourceApks = sourcePaths.map(::File)
            require(sourceApks.isNotEmpty() && sourceApks.all { it.isFile && it.canRead() }) {
                "One or more source APK files are missing or cannot be read."
            }
            val originalPackage = inputData.getString(KEY_ORIGINAL_PACKAGE)
                ?: error("The original package ID is missing.")
            val clonePackage = inputData.getString(KEY_CLONE_PACKAGE)
                ?: error("The clone package ID is missing.")
            val cloneLabel = inputData.getString(KEY_CLONE_LABEL)
                ?: error("The clone app name is missing.")
            val iconSource = inputData.getString(KEY_ICON_PACKAGE)?.let { packageName ->
                if (Build.VERSION.SDK_INT >= 33) {
                    applicationContext.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0L)
                    )
                } else {
                    applicationContext.packageManager.getApplicationInfo(packageName, 0)
                }
            }
            val customIconFile = inputData.getString(KEY_CUSTOM_ICON_PATH)?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }
            val obbSources = inputData.getStringArray(KEY_OBB_FILES).orEmpty().map(::File)
            require(obbSources.all { it.isFile && it.canRead() }) { "One or more OBB expansion files are missing or unreadable." }
            val markIcon = inputData.getBoolean(KEY_MARK_ICON, false)
            val position = inputData.getString(KEY_BADGE_POSITION)
                ?.let { runCatching { BadgePosition.valueOf(it) }.getOrNull() }
                ?: BadgePosition.BOTTOM_RIGHT
            var lastReportedProgress = -1
            val result = ApkCloneEngine(applicationContext).build(
                sourceApks = sourceApks,
                originalPackage = originalPackage,
                newPackage = clonePackage,
                newLabel = cloneLabel,
                outputDirectory = File(applicationContext.filesDir, "openclone/builds/$clonePackage"),
                onProgress = { value ->
                    if (value != lastReportedProgress) {
                        lastReportedProgress = value
                        setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, value).build())
                    }
                },
                iconSource = iconSource.takeIf { markIcon || customIconFile != null },
                allowUpdate = inputData.getBoolean(KEY_ALLOW_UPDATE, false),
                ensureActive = {
                    if (isStopped || Thread.currentThread().isInterrupted) {
                        throw CancellationException("Clone build cancelled.")
                    }
                },
                badgePosition = position,
                markIcon = markIcon,
                customIconFile = customIconFile,
                rewriteDexPackageStrings = inputData.getBoolean(KEY_REWRITE_DEX_PACKAGE_STRINGS, true),
                removedPermissions = inputData.getStringArray(KEY_REMOVED_PERMISSIONS).orEmpty().toSet()
            )
            val obbOutputDirectory = File(result.apks.first().parentFile, "obb")
            val obbOutputs = if (obbSources.isEmpty()) emptyList() else {
                check(obbOutputDirectory.mkdirs() || obbOutputDirectory.isDirectory) { "Could not create the OBB export directory." }
                obbSources.map { source ->
                    if (isStopped || Thread.currentThread().isInterrupted) throw CancellationException("Clone build cancelled.")
                    val renamed = source.name.replace(originalPackage, clonePackage)
                    val destination = File(obbOutputDirectory, renamed)
                    check(!destination.exists()) { "The imported OBB set contains duplicate filenames." }
                    source.copyTo(destination, overwrite = true)
                    destination
                }
            }
            Result.success(
                Data.Builder()
                    .putString(KEY_ORIGINAL_PACKAGE, result.originalPackage)
                    .putString(KEY_CLONE_PACKAGE, result.clonePackage)
                    .putString(KEY_CLONE_LABEL, result.cloneLabel)
                    .putStringArray(KEY_OUTPUT_APKS, result.apks.map { it.absolutePath }.toTypedArray())
                    .putStringArray(KEY_OUTPUT_OBB_FILES, obbOutputs.map { it.absolutePath }.toTypedArray())
                    .putBoolean(KEY_ICON_BADGE_APPLIED, result.iconBadgeApplied)
                    .putBoolean(KEY_CUSTOM_ICON_APPLIED, result.customIconApplied)
                    .putBoolean(KEY_ALLOW_UPDATE, inputData.getBoolean(KEY_ALLOW_UPDATE, false))
                    .build()
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(Data.Builder().putString(KEY_ERROR, error.message ?: "Clone build failed.").build())
    }

    companion object {
        const val KEY_SOURCE_APKS = "source_apks"
        const val KEY_ORIGINAL_PACKAGE = "original_package"
        const val KEY_CLONE_PACKAGE = "clone_package"
        const val KEY_CLONE_LABEL = "clone_label"
        const val KEY_MARK_ICON = "mark_icon"
        const val KEY_REWRITE_DEX_PACKAGE_STRINGS = "rewrite_dex_package_strings"
        const val KEY_REMOVED_PERMISSIONS = "removed_permissions"
        const val KEY_ALLOW_UPDATE = "allow_update"
        const val KEY_ICON_PACKAGE = "icon_package"
        const val KEY_BADGE_POSITION = "badge_position"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_APKS = "output_apks"
        const val KEY_OBB_FILES = "obb_files"
        const val KEY_OUTPUT_OBB_FILES = "output_obb_files"
        const val KEY_ICON_BADGE_APPLIED = "icon_badge_applied"
        const val KEY_CUSTOM_ICON_PATH = "custom_icon_path"
        const val KEY_CUSTOM_ICON_APPLIED = "custom_icon_applied"
        const val KEY_ERROR = "error"
    }
}
