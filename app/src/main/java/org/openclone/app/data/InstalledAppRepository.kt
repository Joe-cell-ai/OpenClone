package org.openclone.app.data

import android.content.pm.PackageManager
import android.os.Build

class InstalledAppRepository(private val packageManager: PackageManager) {
    @Suppress("DEPRECATION")
    fun getInstalledApps(includeSystemApps: Boolean): List<InstalledApp> {
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != "org.openclone.app" }
            .mapNotNull { info ->
                val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                if (!includeSystemApps && system) return@mapNotNull null
                val packageInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        packageManager.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        packageManager.getPackageInfo(info.packageName, 0)
                    }
                }.getOrNull() ?: return@mapNotNull null
                InstalledApp(
                    applicationInfo = info,
                    label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                    packageName = info.packageName,
                    versionName = packageInfo.versionName,
                    versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong(),
                    apkPartCount = 1 + (info.splitSourceDirs?.size ?: 0),
                    isSystemApp = system
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
