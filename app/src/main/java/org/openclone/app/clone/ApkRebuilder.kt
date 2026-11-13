// Adapted from APK Toolbox by bl3xand (GPL-3.0-only). Upstream: https://github.com/bl3xand/APK-Toolbox
package org.openclone.app.clone

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Copies an APK entry by entry, swapping in a patched manifest and renaming the package of the
 * resource table to match. The result is unsigned.
 */
object ApkRebuilder {

    private const val MANIFEST = "AndroidManifest.xml"
    private const val ALIGNMENT_EXTRA_ID = 0xD935
    private const val LOCAL_HEADER_SIZE = 30

    // Native libraries are mapped by page, everything else stored is read by word.
    private const val NATIVE_LIBRARY_ALIGNMENT = 16384
    private const val DEFAULT_ALIGNMENT = 4
    private val SIGNATURE_FILE = Regex("META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)", RegexOption.IGNORE_CASE)

    /**
     * [replacements] swaps whole entries by name. [extraEntry] is evaluated after the manifest
     * was patched, so it may depend on the patcher. With [originalManifest] that is what gets
     * patched instead of the manifest in [source] - for a source that is a clone already, whose
     * own manifest no longer has what the original had.
     */
    fun rebuild(
        source: File,
        target: File,
        patcher: ManifestPatcher,
        replacements: Map<String, ByteArray>,
        extraEntry: (() -> Pair<String, ByteArray>)?,
        originalManifest: ByteArray? = null,
        onStep: (() -> Unit)? = null,
        onProgress: (Int) -> Unit = {},
        dexPackageRewrite: Pair<String, String>? = null,
    ) {
        val counter = CountingOutputStream(BufferedOutputStream(target.outputStream(), 1 shl 16))
        ZipFile(source).use { zip ->
            ZipOutputStream(counter).use { out ->
                val seen = HashSet<String>()
                val totalEntries = zip.entries().asSequence().count().coerceAtLeast(1)
                var processedEntries = 0
                // Patched up front: the resource table needs the old package name, and nothing
                // says the manifest comes before it in the archive.
                val manifest = zip.getEntry(MANIFEST)?.let { entry ->
                    patcher.patch(originalManifest ?: zip.getInputStream(entry).use { it.readBytes() })
                }
                require(manifest != null) { "No AndroidManifest.xml in ${source.name}" }
                // The manifest rewrite is a step like any patch pass; counted up front with the rest.
                onStep?.invoke()
                for (entry in zip.entries()) {
                    onStep?.invoke()
                    if (entry.isDirectory || !seen.add(entry.name)) {
                        processedEntries++
                        onProgress(processedEntries * 100 / totalEntries)
                        continue
                    }
                    if (SIGNATURE_FILE.matches(entry.name) || entry.name.startsWith("META-INF/apkclon") || entry.name == "META-INF/openclone.properties") continue

                    val copy = ZipEntry(entry.name).apply { if (entry.time != -1L) time = entry.time }
                    if (entry.name == MANIFEST) {
                        out.putNextEntry(copy)
                        out.write(manifest)
                    } else if (entry.name == ResourceTableRenamer.ENTRY) {
                        // Same size and place as before; only the package name inside changes. When the
                        // clone keeps the original package name, the table keeps it too, so
                        // getIdentifier(name, type, getPackageName()) still resolves against the original
                        // package.
                        fun renamed(to: OutputStream) = zip.getInputStream(entry).use {
                            val toPackage = if (patcher.keepResourceTable) patcher.oldPackage else patcher.newPackage
                            ResourceTableRenamer.copy(it, to, patcher.oldPackage, toPackage, onStep)
                        }
                        if (entry.method == ZipEntry.STORED) {
                            val checksum = CRC32()
                            renamed(CheckedOutputStream(Discard, checksum))
                            copy.storedAt(counter.count, entry.size, checksum.value)
                        }
                        out.putNextEntry(copy)
                        renamed(out)
                    } else if (dexPackageRewrite != null && entry.name.matches(Regex("classes(\\d*)\\.dex"))) {
                        val sourceDex = File.createTempFile("openclone-source-", ".dex", target.parentFile)
                        val rewrittenDex = File.createTempFile("openclone-rewritten-", ".dex", target.parentFile)
                        try {
                            zip.getInputStream(entry).use { input -> sourceDex.outputStream().use { output -> copyChecked(input, output, onStep) } }
                            onStep?.invoke()
                            val rewrite = DexPackageStringRewriter.rewrite(
                                sourceDex, rewrittenDex, dexPackageRewrite.first, dexPackageRewrite.second
                            )
                            onStep?.invoke()
                            val selectedDex = if (rewrite.replacements > 0) rewrittenDex else sourceDex
                            if (entry.method == ZipEntry.STORED) {
                                val checksum = CRC32()
                                selectedDex.inputStream().use { input -> copyChecked(input, CheckedOutputStream(Discard, checksum), onStep) }
                                copy.storedAt(counter.count, selectedDex.length(), checksum.value)
                            }
                            out.putNextEntry(copy)
                            selectedDex.inputStream().use { input -> copyChecked(input, out, onStep) }
                        } finally {
                            sourceDex.delete()
                            rewrittenDex.delete()
                        }
                    } else if (entry.name in replacements) {
                        out.putNextEntry(copy)
                        out.write(replacements.getValue(entry.name))
                    } else if (entry.method == ZipEntry.STORED) {
                        // resources.arsc and uncompressed native libraries are mmap-ed straight
                        // out of the APK, so they must stay stored and aligned.
                        copy.storedAt(counter.count, entry.size, entry.crc)
                        out.putNextEntry(copy)
                        zip.getInputStream(entry).use { copyChecked(it, out, onStep) }
                    } else {
                        out.putNextEntry(copy)
                        zip.getInputStream(entry).use { copyChecked(it, out, onStep) }
                    }
                    out.closeEntry()
                    processedEntries++
                    onProgress(processedEntries * 100 / totalEntries)
                }
                extraEntry?.invoke()?.let { (name, data) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
    }

    /** Copies in bounded chunks and checks cancellation between chunks. */
    private fun copyChecked(input: java.io.InputStream, output: OutputStream, onStep: (() -> Unit)?) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            onStep?.invoke()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    /** Makes this entry a stored, aligned copy with the given size and checksum. */
    private fun ZipEntry.storedAt(offset: Long, size: Long, checksum: Long) {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        crc = checksum
        val alignment = if (name.endsWith(".so")) NATIVE_LIBRARY_ALIGNMENT else DEFAULT_ALIGNMENT
        val nameLength = name.toByteArray(Charsets.UTF_8).size
        extra = alignmentExtra(offset + LOCAL_HEADER_SIZE + nameLength, alignment)
    }

    /** Same extra field zipalign writes: id, size, alignment, then zero padding. */
    private fun alignmentExtra(dataOffsetWithoutExtra: Long, alignment: Int): ByteArray {
        val padding = ((alignment - (dataOffsetWithoutExtra + 6) % alignment) % alignment).toInt()
        return ByteBuffer.allocate(6 + padding).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(ALIGNMENT_EXTRA_ID.toShort())
            .putShort((2 + padding).toShort())
            .putShort(alignment.toShort())
            .array()
    }

    /** Takes what is written and keeps none of it: only the checksum on the way is wanted. */
    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
