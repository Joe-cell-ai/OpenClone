package org.openclone.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class ApkInspection(
    val fileName: String,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val requestedPermissions: List<String>,
    val compatibilityReport: CloneCompatibilityReport
)

class ApkInspector(private val context: Context) {
    @Suppress("DEPRECATION")
    fun inspect(uri: Uri): ApkInspection {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "selected.apk"
        val tempFile = File.createTempFile("openclone-inspect-", ".apk", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: error("The selected file could not be opened.")
            val inspectionFlags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA or
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    tempFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(inspectionFlags.toLong())
                )
            } else {
                context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, inspectionFlags)
            } ?: error("This file is not a readable Android APK.")
            val appInfo = packageInfo.applicationInfo ?: error("APK application metadata is missing.")
            appInfo.sourceDir = tempFile.absolutePath
            appInfo.publicSourceDir = tempFile.absolutePath
            val componentNames = buildList {
                packageInfo.activities?.forEach { add(it.name) }
                packageInfo.services?.forEach { add(it.name) }
                packageInfo.receivers?.forEach { add(it.name) }
                packageInfo.providers?.forEach { add(it.name) }
            }
            val providerAuthorities = packageInfo.providers
                ?.mapNotNull { it.authority?.takeIf(String::isNotBlank) }.orEmpty()
            return ApkInspection(
                fileName = displayName,
                label = runCatching { appInfo.loadLabel(context.packageManager).toString() }.getOrDefault(packageInfo.packageName),
                packageName = packageInfo.packageName,
                versionName = packageInfo.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong(),
                minSdk = appInfo.minSdkVersion,
                targetSdk = appInfo.targetSdkVersion,
                requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty(),
                compatibilityReport = CloneCompatibilityAnalyzer.analyze(
                    packageName = packageInfo.packageName,
                    requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty(),
                    metadataKeys = appInfo.metaData?.keySet()?.toList().orEmpty(),
                    componentNames = componentNames,
                    providerAuthorities = providerAuthorities,
                    sharedUserId = packageInfo.sharedUserId
                )
            )
        } finally {
            tempFile.delete()
        }
    }
}
