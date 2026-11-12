package org.openclone.app.data

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

class InstalledCloneRepository(private val packageManager: PackageManager) {
    @Suppress("DEPRECATION")
    fun findClones(apps: List<InstalledApp>): List<InstalledClone> {
        return apps.mapNotNull { app ->
            val apkPath = app.applicationInfo.sourceDir ?: return@mapNotNull null
            runCatching {
                ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(METADATA_ENTRY) ?: return@use null
                    val properties = Properties()
                    zip.getInputStream(entry).use { input -> properties.load(input) }
                    val original = properties.getProperty("originalPackage").orEmpty()
                    val clonePackage = properties.getProperty("clonePackage").orEmpty()
                    val recordedFingerprint = properties.getProperty("signingFingerprint").orEmpty()
                    val installedFingerprint = signingFingerprint(app.packageName)
                    if (
                        original.isBlank() ||
                        clonePackage != app.packageName ||
                        recordedFingerprint.isBlank() ||
                        recordedFingerprint != installedFingerprint
                    ) {
                        null
                    } else {
                        InstalledClone(
                            app = app,
                            originalPackage = original,
                            clonePackage = clonePackage,
                            cloneLabel = properties.getProperty("cloneLabelBase64")?.let { encoded ->
                                runCatching {
                                    String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                                }.getOrNull()
                            } ?: app.label,
                            createdAtMillis = properties.getProperty("createdAt")?.toLongOrNull() ?: 0L,
                            signingFingerprint = recordedFingerprint
                        )
                    }
                }
            }.getOrNull()
        }.filterNotNull().sortedBy { it.cloneLabel.lowercase() }
    }

    private fun signingFingerprint(packageName: String): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
        val certificate = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private companion object {
        const val METADATA_ENTRY = "META-INF/openclone.properties"
    }
}
