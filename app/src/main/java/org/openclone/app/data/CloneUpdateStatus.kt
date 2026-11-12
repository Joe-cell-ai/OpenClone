package org.openclone.app.data

/** Pure version-code comparison used to decide whether a clone can be rebuilt from its source. */
internal object CloneUpdateStatus {
    fun isUpdateAvailable(cloneSourceVersionCode: Long, installedOriginalVersionCode: Long?): Boolean =
        installedOriginalVersionCode != null && installedOriginalVersionCode > cloneSourceVersionCode
}
