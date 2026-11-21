package org.openclone.app.clone

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.DexBuilder
import org.jf.dexlib2.builder.MethodImplementationBuilder
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.AccessFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexPackageStringRewriterIntegrationTest {
    @Test
    fun rewritesFlattenedWhatsAppRegistrationStringInsideRealDexAndKeepsClassNames() {
        val directory = createTempDir(prefix = "openclone-dex-test-")
        val input = File(directory, "classes.dex")
        val output = File(directory, "rewritten.dex")
        try {
            val dexBuilder = DexBuilder(Opcodes.getDefault())
            val implementation = MethodImplementationBuilder(1).apply {
                addInstruction(
                    BuilderInstruction21c(
                        Opcode.CONST_STRING,
                        0,
                        dexBuilder.internString(
                            "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"
                        )
                    )
                )
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }.methodImplementation
            val method = dexBuilder.internMethod(
                "Lcom/whatsapp/Registration;",
                "launchRegistration",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                emptySet(),
                emptySet(),
                implementation
            )
            dexBuilder.internClassDef(
                "Lcom/whatsapp/Registration;",
                AccessFlags.PUBLIC.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                null,
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(input.absolutePath)

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val rewrittenDex = DexFileFactory.loadDexFile(output, Opcodes.getDefault())
            val rewrittenClass = rewrittenDex.classes.single()
            assertEquals("Lcom/whatsapp/Registration;", rewrittenClass.type)
            val strings = rewrittenClass.methods
                .flatMap { it.implementation?.instructions?.toList().orEmpty() }
                .mapNotNull { instruction ->
                    (instruction as? ReferenceInstruction)?.reference as? StringReference
                }
                .map { it.string }
            assertTrue(
                strings.contains(
                    "com.whatsapp.clone/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
                )
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
