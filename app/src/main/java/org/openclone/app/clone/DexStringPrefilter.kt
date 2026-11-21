package org.openclone.app.clone

import java.io.File

/**
 * Cheap check that tells the DEX package-string rewrite when a DEX file cannot change.
 *
 * [DexPackageStringRewriter] changes a string only when it equals the original package or contains
 * "<package>/". Strings live in a DEX file as plain bytes (modified UTF-8, which is ASCII for package
 * names) and each ends with a zero byte. A file with neither byte pattern therefore holds no string
 * the rewrite would change, and the caller already discards the rewrite's output when nothing was
 * replaced. Skipping such a file produces the same APK, without parsing, copying and re-writing the
 * whole DEX.
 *
 * The check is a superset test: it may say "yes" for a file the rewrite then leaves unchanged (for
 * example a string that merely ends with the package name), but it never says "no" for a file the
 * rewrite would change.
 */
object DexStringPrefilter {
    private const val CHUNK = 1 shl 16

    fun mayContainRewritableStrings(dex: File, oldPackage: String): Boolean {
        val packageBytes = oldPackage.toByteArray(Charsets.UTF_8)
        val exactString = packageBytes + 0.toByte()
        val withSlash = packageBytes + '/'.code.toByte()
        val needles = listOf(exactString, withSlash)
        val keep = needles.maxOf { it.size } - 1
        val buffer = ByteArray(CHUNK + keep)
        var found = false
        dex.inputStream().use { input ->
            var carried = 0
            while (!found) {
                val read = input.read(buffer, carried, CHUNK)
                if (read < 0) break
                val length = carried + read
                found = needles.any { contains(buffer, length, it) }
                if (!found) {
                    carried = minOf(keep, length)
                    System.arraycopy(buffer, length - carried, buffer, 0, carried)
                }
            }
        }
        return found
    }

    private fun contains(haystack: ByteArray, length: Int, needle: ByteArray): Boolean {
        val last = length - needle.size
        var start = 0
        while (start <= last) {
            if (haystack[start] == needle[0]) {
                var matched = 1
                while (matched < needle.size && haystack[start + matched] == needle[matched]) matched++
                if (matched == needle.size) return true
            }
            start++
        }
        return false
    }
}
