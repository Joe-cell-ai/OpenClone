package org.openclone.app.virtualspace

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class VirtualApkDescriptor(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val splitNames: List<String>,
    val requestedPermissions: List<String>,
    val containsCode: Boolean
)

class VirtualApkInspector(private val context: Context) {
    @Suppress("DEPRECATION")
    fun inspect(apkFiles: List<File>): VirtualApkDescriptor {
        require(apkFiles.isNotEmpty()) { "Select an APK or complete split-APK set." }
        require(apkFiles.size <= MAX_SPLITS) { "This APK set contains too many split files." }
        require(apkFiles.all { it.isFile && it.canRead() && it.length() > 0L }) {
            "One or more APK files are missing, empty, or unreadable."
        }

        val packageInfos = apkFiles.map { file ->
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            } ?: error("${file.name} is not a valid Android APK.")
            file to info
        }

        val baseCandidates = packageInfos.filter { (_, info) -> info.splitNames.isNullOrEmpty() }
        require(baseCandidates.size == 1) {
            "The APK set must contain exactly one base APK."
        }
        val (baseFile, base) = baseCandidates.single()
        val packageName = base.packageName
        val versionCode = base.versionCodeCompat()
        require(packageInfos.all { (_, info) ->
            info.packageName == packageName && info.versionCodeCompat() == versionCode
        }) { "The APK set mixes different apps or versions." }

        val splitNames = packageInfos.flatMap { (_, info) -> info.splitNames?.toList().orEmpty() }
        require(splitNames.size == splitNames.distinct().size) {
            "The APK set contains duplicate split names."
        }

        val appInfo = base.applicationInfo ?: error("The base APK has no application metadata.")
        appInfo.sourceDir = baseFile.absolutePath
        appInfo.publicSourceDir = baseFile.absolutePath
        val label = runCatching {
            appInfo.loadLabel(context.packageManager).toString()
        }.getOrDefault(packageName).ifBlank { packageName }

        return VirtualApkDescriptor(
            packageName = packageName,
            label = label,
            versionCode = versionCode,
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            splitNames = splitNames,
            requestedPermissions = base.requestedPermissions?.toList().orEmpty(),
            containsCode = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_HAS_CODE != 0
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private companion object {
        const val MAX_SPLITS = 128
    }
}
