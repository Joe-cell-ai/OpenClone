package org.openclone.app.data

import java.util.Properties
import java.util.zip.ZipFile

class InstalledCloneRepository {
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
                    if (original.isBlank() || clonePackage != app.packageName) {
                        null
                    } else {
                        InstalledClone(
                            app = app,
                            originalPackage = original,
                            clonePackage = clonePackage,
                            cloneLabel = properties.getProperty("cloneLabelBase64")?.let { encoded ->
                                runCatching { String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
                            } ?: app.label,
                            createdAtMillis = properties.getProperty("createdAt")?.toLongOrNull() ?: 0L,
                            signingFingerprint = properties.getProperty("signingFingerprint").orEmpty()
                        )
                    }
                }
            }.getOrNull()
        }.filterNotNull().sortedBy { it.cloneLabel.lowercase() }
    }

    private companion object {
        const val METADATA_ENTRY = "META-INF/openclone.properties"
    }
}
