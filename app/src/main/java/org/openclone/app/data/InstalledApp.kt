package org.openclone.app.data

import android.content.pm.ApplicationInfo

data class InstalledApp(
    val applicationInfo: ApplicationInfo,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val apkPartCount: Int,
    val isSystemApp: Boolean
)
