package org.openclone.app.virtualspace

import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Persistent, non-root storage for a virtual app instance.
 *
 * This is the storage/registry foundation only. It does not execute APK code or claim
 * Parallel Space compatibility; guest execution requires the separate virtualization runtime.
 */
data class VirtualAppInstance(
    val id: String,
    val originalPackage: String,
    val label: String,
    val apkFile: File,
    val privateDataDirectory: File,
    val createdAtMillis: Long
)

class VirtualSpaceStore(rootDirectory: File) {
    private val root = rootDirectory.canonicalFile
    private val instancesDirectory = File(root, "instances").canonicalFile

    init {
        check(instancesDirectory.mkdirs() || instancesDirectory.isDirectory) {
            "Could not create virtual-space storage."
        }
    }

    @Synchronized
    fun createInstance(originalPackage: String, label: String, sourceApk: File): VirtualAppInstance {
        require(PACKAGE_ID.matches(originalPackage)) { "Invalid Android package name." }
        require(label.isNotBlank() && label.length <= 100) { "App name must contain 1–100 characters." }
        require(sourceApk.isFile && sourceApk.canRead()) { "The source APK is unavailable." }

        val id = UUID.randomUUID().toString()
        val directory = instanceDirectory(id)
        check(directory.mkdirs()) { "Could not create the virtual app directory." }

        try {
            val apk = File(directory, "base.apk")
            val temporaryApk = File(directory, "base.apk.tmp")
            sourceApk.inputStream().use { input ->
                temporaryApk.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporaryApk.renameTo(apk)) { "Could not save the app APK." }

            val dataDirectory = File(directory, "data")
            check(dataDirectory.mkdirs()) { "Could not create private app storage." }

            val metadata = Properties().apply {
                setProperty("formatVersion", FORMAT_VERSION.toString())
                setProperty("id", id)
                setProperty("originalPackage", originalPackage)
                setProperty("label", label)
                setProperty("createdAtMillis", System.currentTimeMillis().toString())
            }
            val metadataFile = File(directory, METADATA_FILE)
            val temporaryMetadata = File(directory, "$METADATA_FILE.tmp")
            temporaryMetadata.outputStream().use { metadata.store(it, "OpenClone virtual app instance") }
            check(temporaryMetadata.renameTo(metadataFile)) { "Could not save virtual app metadata." }

            return readInstance(directory) ?: error("Saved virtual app instance could not be verified.")
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun listInstances(): List<VirtualAppInstance> =
        instancesDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { directory -> runCatching { readInstance(directory) }.getOrNull() }
            .sortedBy { it.label.lowercase() }
            .toList()

    @Synchronized
    fun findInstance(id: String): VirtualAppInstance? =
        runCatching { readInstance(instanceDirectory(id)) }.getOrNull()

    @Synchronized
    fun deleteInstance(id: String): Boolean {
        val directory = instanceDirectory(id)
        if (!directory.exists()) return false
        check(directory.parentFile?.canonicalFile == instancesDirectory) {
            "Refusing to delete a path outside virtual-space storage."
        }
        return directory.deleteRecursively()
    }

    private fun readInstance(directory: File): VirtualAppInstance? {
        val canonical = directory.canonicalFile
        if (canonical.parentFile != instancesDirectory || !canonical.isDirectory) return null
        val metadataFile = File(canonical, METADATA_FILE)
        val apk = File(canonical, "base.apk")
        val data = File(canonical, "data")
        if (!metadataFile.isFile || !apk.isFile || !data.isDirectory) return null

        val properties = Properties().apply { metadataFile.inputStream().use { load(it) } }
        val id = properties.getProperty("id") ?: return null
        if (id != canonical.name || !UUID_PATTERN.matches(id)) return null
        if (properties.getProperty("formatVersion")?.toIntOrNull() != FORMAT_VERSION) return null
        val originalPackage = properties.getProperty("originalPackage") ?: return null
        if (!PACKAGE_ID.matches(originalPackage)) return null
        val label = properties.getProperty("label")?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = properties.getProperty("createdAtMillis")?.toLongOrNull() ?: return null

        return VirtualAppInstance(id, originalPackage, label, apk, data, createdAt)
    }

    private fun instanceDirectory(id: String): File {
        require(UUID_PATTERN.matches(id)) { "Invalid virtual app instance ID." }
        return File(instancesDirectory, id).canonicalFile
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val METADATA_FILE = "instance.properties"
        val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
