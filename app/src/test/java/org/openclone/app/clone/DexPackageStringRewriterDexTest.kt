package org.openclone.app.clone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.dexlib2.writer.io.FileDataStore

/**
 * Exercises the rewriter against a real DEX container and const-string instruction,
 * rather than testing only the string helper.
 */
class DexPackageStringRewriterDexTest {
    @Test
    fun rewritesFlattenedWhatsAppComponentInsideDexInstruction() {
        val directory = createTempDir(prefix = "openclone-dex-test-")
        val input = File(directory, "input.dex")
        val output = File(directory, "output.dex")
        try {
            writeDex(input, "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone")

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val rewritten = DexFileFactory.loadDexFile(output, Opcodes.getDefault())
            val strings = mutableListOf<String>()
            for (classDef in rewritten.classes) {
                for (method in classDef.methods) {
                    val instructions = method.implementation?.instructions ?: continue
                    for (instruction in instructions) {
                        val reference = (instruction as? org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                            ?.reference as? org.jf.dexlib2.iface.reference.StringReference
                        if (reference != null) strings += reference.string
                    }
                }
            }
            assertTrue(strings.contains(
                "com.whatsapp.clone/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
            ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun scannerReadsRealDexAndFindsFlattenedComponentLiteral() {
        val directory = createTempDir(prefix = "openclone-scan-test-")
        val dex = File(directory, "fixture.dex")
        try {
            writeDex(dex, "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone")
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

    private fun writeDex(file: File, literal: String) {
        val builder = DexBuilder(Opcodes.getDefault())
        val implementation = MutableMethodImplementation(1)
        implementation.addInstruction(
            BuilderInstruction21c(
                Opcode.CONST_STRING,
                0,
                builder.internStringReference(literal)
            )
        )
        implementation.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = builder.internMethod(
            "Lcom/whatsapp/DiagnosticFixture;",
            "component",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            implementation
        )
        builder.internClassDef(
            "Lcom/whatsapp/DiagnosticFixture;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            listOf(method)
        )
        FileDataStore(file).use { dataStore ->
            builder.writeTo(dataStore)
        }
    }
}
