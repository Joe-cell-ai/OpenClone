package org.openclone.app.clone

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexStringPrefilterTest {
    private val old = "com.whatsapp"
    private val clone = "com.whatsapp.clone"

    private fun fileOf(bytes: ByteArray): File =
        Files.createTempFile("prefilter-", ".bin").toFile().also { it.deleteOnExit(); it.writeBytes(bytes) }

    private fun stringData(value: String): ByteArray =
        byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.UTF_8) + 0.toByte()

    private fun fileWithStrings(vararg values: String): File =
        fileOf(values.fold(ByteArray(0)) { acc, v -> acc + stringData(v) })

    @Test fun findsTheExactPackageString() {
        assertTrue(DexStringPrefilter.mayContainRewritableStrings(fileWithStrings("a", old, "b"), old))
    }

    @Test fun findsAFlattenedComponentString() {
        assertTrue(DexStringPrefilter.mayContainRewritableStrings(
            fileWithStrings("com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"), old))
    }

    @Test fun skipsAFileWithOnlyOtherIdentifiers() {
        val other = fileWithStrings("com.whatsapp.permission.BROADCAST", "com.whatsapp.provider.media",
            "com.whatsapp_preferences", "com.whatsapp.registration.app.phonenumberentry.RegisterPhone",
            "com.whatsappx", "whatsapp")
        assertFalse(DexStringPrefilter.mayContainRewritableStrings(other, old))
    }

    @Test fun anEmptyFileHasNothingToRewrite() {
        assertFalse(DexStringPrefilter.mayContainRewritableStrings(fileOf(ByteArray(0)), old))
    }

    @Test fun findsAMatchThatStraddlesTheReadBoundary() {
        val chunk = 1 shl 16
        val needle = stringData(old)
        for (offset in (chunk - needle.size - 2)..(chunk + 2)) {
            val bytes = ByteArray(chunk * 3) { 'x'.code.toByte() }
            needle.copyInto(bytes, offset)
            assertTrue("match placed at $offset", DexStringPrefilter.mayContainRewritableStrings(fileOf(bytes), old))
        }
    }

    @Test fun findsASlashMatchThatStraddlesTheReadBoundary() {
        val chunk = 1 shl 16
        val needle = "$old/".toByteArray(Charsets.UTF_8)
        for (offset in (chunk - needle.size - 2)..(chunk + 2)) {
            val bytes = ByteArray(chunk * 2) { 'x'.code.toByte() }
            needle.copyInto(bytes, offset)
            assertTrue("match placed at $offset", DexStringPrefilter.mayContainRewritableStrings(fileOf(bytes), old))
        }
    }

    @Test fun neverSaysNoForAStringTheRewriteRuleChanges() {
        val samples = listOf(old, "$old/.X", "$old/$old.Y", "$old/", "intent:#Intent;component=$old/.X;end",
            " $old/.X", "a.$old/.X", "${old}x/.X", "$old.registration.A", "x$old", "$old ", old.uppercase(),
            "$old/.registration.app.phonenumberentry.RegisterPhone", "L${old.replace('.', '/')};", "")
        var changing = 0
        for (sample in samples) {
            val changed = DexPackageStringRewriter.replacePackageIdentityToken(sample, old, clone) != sample
            if (changed) {
                changing++
                assertTrue("rewrite changes \"$sample\" so the prefilter must not skip it",
                    DexStringPrefilter.mayContainRewritableStrings(fileWithStrings(sample), old))
            }
        }
        assertTrue("the sample set exercises the rule ($changing changing samples)", changing >= 4)
    }

    @Test fun sameResultOnALargeFileWithTheMatchAtTheVeryEnd() {
        val bytes = ByteArray(5_000_000) { 'x'.code.toByte() }
        stringData(old).copyInto(bytes, bytes.size - stringData(old).size)
        assertEquals(true, DexStringPrefilter.mayContainRewritableStrings(fileOf(bytes), old))
    }
}
