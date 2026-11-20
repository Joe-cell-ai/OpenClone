package org.openclone.app.clone

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.dexlib2.writer.io.FileDataStore

/**
 * Exercises package-string rewriting against real DEX containers and an APK ZIP.
 * These tests cover safe string-only rewriting; class/type descriptors are not renamed.
 */
class DexPackageStringRewriterDexTest {
    @Test
    fun rewritesFlattenedWhatsAppComponentInsideDexInstruction() {
        val directory = createTempDir(prefix = "openclone-dex-test-")
        val input = File(directory, "input.dex")
        val output = File(directory, "output.dex")
        try {
            val literal = "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"
            writeDex(input, listOf(literal), literal)

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val rewritten = readDex(output)
            assertTrue(rewritten.instructionStrings.contains(
                "com.whatsapp.clone/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
            ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun apkRebuildRewritesEveryDexAndEncodedStringButPreservesUnrelatedTokensAndDescriptors() {
        val directory = createTempDir(prefix = "openclone-multidex-test-")
        val baseDex = File(directory, "base.dex")
        val secondaryDex = File(directory, "secondary.dex")
        val sourceApk = File(directory, "source.apk")
        val rebuiltApk = File(directory, "rebuilt.apk")
        try {
            val baseComponent =
                "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"
            val secondaryComponent = "com.whatsapp/.settings.SettingsActivity"
            val unrelatedComponent = "xcom.whatsapp/.NotAComponent"
            writeDex(baseDex, listOf(baseComponent), baseComponent)
            writeDex(
                secondaryDex,
                listOf(secondaryComponent, unrelatedComponent),
                "xcom.whatsappish"
            )
            writeApk(
                sourceApk,
                binaryManifest(),
                linkedMapOf("classes.dex" to baseDex, "classes2.dex" to secondaryDex)
            )

            ApkRebuilder.rebuild(
                source = sourceApk,
                target = rebuiltApk,
                patcher = ManifestPatcher("com.whatsapp.clone", null),
                replacements = emptyMap(),
                extraEntry = null,
                dexPackageRewrite = "com.whatsapp" to "com.whatsapp.clone"
            )

            ZipFile(rebuiltApk).use { zip ->
                val base = readDexEntry(zip, "classes.dex", File(directory, "read-base.dex"))
                assertTrue(base.instructionStrings.contains(
                    "com.whatsapp.clone/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
                ))
                assertTrue(base.encodedStrings.contains(
                    "com.whatsapp.clone/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
                ))

                val secondary = readDexEntry(zip, "classes2.dex", File(directory, "read-secondary.dex"))
                assertTrue(secondary.instructionStrings.contains(
                    "com.whatsapp.clone/com.whatsapp.settings.SettingsActivity"
                ))
                assertTrue(secondary.instructionStrings.contains(unrelatedComponent))
                assertTrue(secondary.encodedStrings.contains("xcom.whatsappish"))
                assertEquals(setOf("Lcom/whatsapp/DiagnosticFixture;"), base.classDescriptors)
                assertEquals(setOf("Lcom/whatsapp/DiagnosticFixture;"), secondary.classDescriptors)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun propagatesCancellationDuringInstructionTraversalBeforeWritingOutput() {
        val directory = createTempDir(prefix = "openclone-dex-cancel-test-")
        val input = File(directory, "input.dex")
        val output = File(directory, "output.dex")
        try {
            writeDex(
                input,
                listOf("com.whatsapp/.RegistrationActivity"),
                "com.whatsapp/.RegistrationActivity"
            )
            var checkpoints = 0
            var cancelled = false
            try {
                DexPackageStringRewriter.rewrite(
                    input = input,
                    output = output,
                    oldPackage = "com.whatsapp",
                    newPackage = "com.whatsapp.clone",
                    ensureActive = {
                        checkpoints++
                        if (checkpoints == 2) throw CancellationException("test cancellation")
                    }
                )
            } catch (error: CancellationException) {
                cancelled = true
            }

            assertTrue("cancellation should be observed during DEX traversal", cancelled)
            assertTrue("the traversal checkpoint should have run", checkpoints >= 2)
            assertFalse("cancelled rewrite must not create an output DEX", output.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun scannerReadsRealDexAndFindsFlattenedComponentLiteral() {
        val directory = createTempDir(prefix = "openclone-scan-test-")
        val dex = File(directory, "fixture.dex")
        try {
            writeDex(
                dex,
                listOf("com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"),
                "com.whatsapp"
            )
            val findings = DexComponentScanner.scan(dex, "com.whatsapp")
            assertTrue(findings.any { finding ->
                finding.shape == DexComponentScanner.Shape.FLATTENED &&
                    finding.literals.contains(
                        "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"
                    )
            })
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class DexContents(
        val instructionStrings: List<String>,
        val encodedStrings: List<String>,
        val classDescriptors: Set<String>
    )

    private fun readDex(file: File): DexContents {
        val dex = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
        val instructionStrings = mutableListOf<String>()
        val encodedStrings = mutableListOf<String>()
        for (classDef in dex.classes) {
            for (field in classDef.staticFields) {
                val value = field.initialValue
                if (value is StringEncodedValue) encodedStrings += value.value
            }
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                for (instruction in instructions) {
                    val reference = (instruction as? org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                        ?.reference as? org.jf.dexlib2.iface.reference.StringReference
                    if (reference != null) instructionStrings += reference.string
                }
            }
        }
        return DexContents(instructionStrings, encodedStrings, dex.classes.map { it.type }.toSet())
    }

    private fun readDexEntry(zip: ZipFile, entryName: String, target: File): DexContents {
        val entry = requireNotNull(zip.getEntry(entryName)) { "Missing $entryName in rebuilt APK" }
        zip.getInputStream(entry).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return readDex(target)
    }

    private fun writeDex(file: File, literals: List<String>, encodedValue: String) {
        val builder = DexBuilder(Opcodes.getDefault())
        val implementation = MutableMethodImplementation(1)
        literals.forEach { literal ->
            implementation.addInstruction(
                BuilderInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    builder.internStringReference(literal)
                )
            )
        }
        implementation.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val classDescriptor = "Lcom/whatsapp/DiagnosticFixture;"
        val method = builder.internMethod(
            classDescriptor,
            "component",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            implementation
        )
        val field = builder.internField(
            classDescriptor,
            "packageIdentity",
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
            ImmutableStringEncodedValue(encodedValue),
            emptySet(),
            emptySet()
        )
        builder.internClassDef(
            classDescriptor,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            listOf(field),
            listOf(method)
        )
        val dataStore = FileDataStore(file)
        try {
            builder.writeTo(dataStore)
        } finally {
            dataStore.close()
        }
    }

    private fun binaryManifest(): ByteArray {
        val document = ResXmlDocument()
        val root = document.newElement("manifest")
        root.getOrCreateAttribute(null, null, "package", 0).setValueAsString("com.whatsapp")
        root.newElement("application")
        document.refreshFull()
        return document.bytes
    }

    private fun writeApk(apk: File, manifest: ByteArray, dexFiles: Map<String, File>) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("AndroidManifest.xml", manifest)
            dexFiles.forEach { (name, file) -> add(name, file.readBytes()) }
        }
    }
}
