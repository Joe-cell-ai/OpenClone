package org.openclone.app.data

/** Pure version-code comparison used to decide whether a clone can be rebuilt from its source. */
internal object CloneUpdateStatus {
    fun isUpdateAvailable(cloneSourceVersionCode: Long, installedOriginalVersionCode: Long?): Boolean =
        cloneSourceVersionCode > 0L &&
            installedOriginalVersionCode != null &&
            installedOriginalVersionCode > cloneSourceVersionCode

    fun label(cloneSourceVersionCode: Long, installedOriginalVersionCode: Long?): String = when {
        cloneSourceVersionCode <= 0L -> "Version unknown"
        installedOriginalVersionCode == null -> "Original app unavailable"
        installedOriginalVersionCode > cloneSourceVersionCode -> "Update available"
        installedOriginalVersionCode < cloneSourceVersionCode -> "Original app older"
        else -> "Up to date"
    }
}
