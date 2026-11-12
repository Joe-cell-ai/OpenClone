package org.openclone.app.data

data class InstalledClone(
    val app: InstalledApp,
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val createdAtMillis: Long,
    val signingFingerprint: String,
    val sourceVersionCode: Long = 0L,
    val installedSourceVersionCode: Long? = null
) {
    val sourceUpdateAvailable: Boolean
        get() = installedSourceVersionCode != null && sourceVersionCode > 0L && installedSourceVersionCode > sourceVersionCode
}
