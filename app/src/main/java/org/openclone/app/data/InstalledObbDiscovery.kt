package org.openclone.app.data

import java.io.File

/** Read-only discovery of expansion files belonging to an installed package. */
object InstalledObbDiscovery {
    enum class Status {
        FOUND,
        NOT_FOUND,
        PERMISSION_REQUIRED,
        SCOPED_STORAGE_RESTRICTED,
        STORAGE_UNAVAILABLE,
        INACCESSIBLE,
        INVALID_PACKAGE
    }

    data class Result(
        val status: Status,
        val files: List<File> = emptyList()
    )

    private val packageNamePattern =
        Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * Android 10+ scoped storage prevents ordinary apps from enumerating another app's
     * Android/obb directory. We intentionally support direct discovery only on API 26–28.
     *
     * [obbRoot] is normally <external-storage>/Android/obb. It is injected for tests.
     */
    fun inspect(
        packageName: String,
        sdkInt: Int,
        hasReadPermission: Boolean,
        obbRoot: File,
        storageReadable: Boolean
    ): Result {
        if (!packageNamePattern.matches(packageName)) return Result(Status.INVALID_PACKAGE)
        if (sdkInt >= 29) return Result(Status.SCOPED_STORAGE_RESTRICTED)
        if (!hasReadPermission) return Result(Status.PERMISSION_REQUIRED)
        if (!storageReadable) return Result(Status.STORAGE_UNAVAILABLE)

        if (!obbRoot.exists()) return Result(Status.NOT_FOUND)
        if (!obbRoot.isDirectory || !obbRoot.canRead()) return Result(Status.INACCESSIBLE)

        val canonicalRoot = runCatching { obbRoot.canonicalFile }.getOrNull()
            ?: return Result(Status.INACCESSIBLE)
        val packageDirectory = File(obbRoot, packageName)
        if (!packageDirectory.exists()) return Result(Status.NOT_FOUND)
        if (!packageDirectory.isDirectory || !packageDirectory.canRead()) {
            return Result(Status.INACCESSIBLE)
        }

        val canonicalDirectory = runCatching { packageDirectory.canonicalFile }.getOrNull()
            ?: return Result(Status.INACCESSIBLE)
        if (canonicalDirectory.parentFile != canonicalRoot || canonicalDirectory.name != packageName) {
            return Result(Status.INACCESSIBLE)
        }

        val entries = packageDirectory.listFiles() ?: return Result(Status.INACCESSIBLE)
        val obbEntries = entries.filter { it.name.endsWith(".obb", ignoreCase = true) }
        val readableFiles = obbEntries.filter { file ->
            file.isFile && file.canRead() &&
                runCatching { file.canonicalFile.parentFile == canonicalDirectory }.getOrDefault(false)
        }.sortedBy { it.name.lowercase() }

        return when {
            readableFiles.isNotEmpty() -> Result(Status.FOUND, readableFiles)
            obbEntries.isNotEmpty() -> Result(Status.INACCESSIBLE)
            else -> Result(Status.NOT_FOUND)
        }
    }
}
