package org.openclone.app.virtualspace

import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Private storage and package registry for guest APKs.
 *
 * This is the persistent container layer, not yet an Android component virtualization engine.
 */
data class VirtualAppInstance(
    val id: String,
    val originalPackage: String,
    val label: String,
    val apkFiles: List<File>,
    val privateDataDirectory: File,
    val createdAtMillis: Long
) {
    val apkFile: File get() = apkFiles.first()
}

class VirtualSpaceStore(rootDirectory: File) {
    private val root = rootDirectory.canonicalFile
    private val instancesDirectory = File(root, "instances").canonicalFile

    init {
        check(instancesDirectory.mkdirs() || instancesDirectory.isDirectory) {
            "Could not create virtual-space storage."
        }
    }

    @Synchronized
    fun createInstance(originalPackage: String, label: String, sourceApk: File): VirtualAppInstance =
        createInstance(originalPackage, label, listOf(sourceApk))

    @Synchronized
    fun createInstance(originalPackage: String, label: String, sourceApks: List<File>): VirtualAppInstance {
        require(PACKAGE_ID.matches(originalPackage)) { "Invalid Android package name." }
        require(label.isNotBlank() && label.length <= 100) { "App name must contain 1–100 characters." }
        require(sourceApks.isNotEmpty() && sourceApks.size <= MAX_APK_PARTS) { "The APK set is empty or contains too many split files." }
        require(sourceApks.all { it.isFile && it.canRead() }) { "One or more source APK files are unavailable." }

        val id = UUID.randomUUID().toString()
        val directory = instanceDirectory(id)
        check(directory.mkdirs()) { "Could not create the virtual app directory." }

        try {
            val copiedApks = sourceApks.mapIndexed { index, source ->
                val target = File(directory, if (index == 0) "base.apk" else "split_$index.apk")
                val temporary = File(directory, "${target.name}.tmp")
                source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                check(temporary.renameTo(target)) { "Could not save ${source.name}." }
                target
            }
            val dataDirectory = File(directory, "data")
            check(dataDirectory.mkdirs()) { "Could not create private app storage." }

            val metadata = Properties().apply {
                setProperty("formatVersion", FORMAT_VERSION.toString())
                setProperty("id", id)
                setProperty("originalPackage", originalPackage)
                setProperty("label", label)
                setProperty("apkCount", copiedApks.size.toString())
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
        instancesDirectory.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory }
            .mapNotNull { directory -> runCatching { readInstance(directory) }.getOrNull() }
            .sortedBy { it.label.lowercase() }.toList()

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
        val data = File(canonical, "data")
        if (!metadataFile.isFile || !data.isDirectory) return null

        val properties = Properties().apply { metadataFile.inputStream().use { load(it) } }
        val id = properties.getProperty("id") ?: return null
        if (id != canonical.name || !UUID_PATTERN.matches(id)) return null
        if (properties.getProperty("formatVersion")?.toIntOrNull() != FORMAT_VERSION) return null
        val originalPackage = properties.getProperty("originalPackage") ?: return null
        if (!PACKAGE_ID.matches(originalPackage)) return null
        val label = properties.getProperty("label")?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = properties.getProperty("createdAtMillis")?.toLongOrNull() ?: return null
        val apkCount = properties.getProperty("apkCount")?.toIntOrNull()?.takeIf { it in 1..MAX_APK_PARTS } ?: return null
        val apks = (0 until apkCount).map { index ->
            File(canonical, if (index == 0) "base.apk" else "split_$index.apk")
        }
        if (apks.any { !it.isFile || it.length() == 0L }) return null

        return VirtualAppInstance(id, originalPackage, label, apks, data, createdAt)
    }

    private fun instanceDirectory(id: String): File {
        require(UUID_PATTERN.matches(id)) { "Invalid virtual app instance ID." }
        return File(instancesDirectory, id).canonicalFile
    }

    private companion object {
        const val FORMAT_VERSION = 2
        const val METADATA_FILE = "instance.properties"
        const val MAX_APK_PARTS = 128
        val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
