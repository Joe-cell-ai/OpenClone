package org.openclone.app.data

data class InstalledClone(
    val app: InstalledApp,
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val createdAtMillis: Long,
    val signingFingerprint: String
)
