package org.openclone.app.clone

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.builder.MethodImplementationBuilder
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction31c
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
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
                        dexBuilder.internStringReference(
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
                emptySet(),
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(FileDataStore(input))

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val audit = DexPackageStringRewriter.auditComponentReferences(
                output, "com.whatsapp", "com.whatsapp.clone"
            )
            assertEquals(0, audit.oldPackageLiterals)
            assertEquals(0, audit.oldFlattenedComponentReferences)
            assertTrue(audit.cloneFlattenedComponentReferences > 0)
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
    @Test
    fun rewritesWhatsAppRegistrationStringStoredInConstStringJumbo() {
        val directory = createTempDir(prefix = "openclone-dex-jumbo-test-")
        val input = File(directory, "classes.dex")
        val output = File(directory, "rewritten.dex")
        try {
            val dexBuilder = DexBuilder(Opcodes.getDefault())
            val implementation = MethodImplementationBuilder(1).apply {
                addInstruction(
                    BuilderInstruction31c(
                        Opcode.CONST_STRING_JUMBO,
                        0,
                        dexBuilder.internStringReference(
                            "com.whatsapp/.registration.app.phonenumberentry.RegisterPhone"
                        )
                    )
                )
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }.methodImplementation
            val method = dexBuilder.internMethod(
                "Lcom/whatsapp/Registration;",
                "launchRegistrationJumbo",
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
                emptySet(),
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(FileDataStore(input))

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val rewrittenDex = DexFileFactory.loadDexFile(output, Opcodes.getDefault())
            val strings = rewrittenDex.classes.single().methods
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

    @Test
    fun rewritesFullyQualifiedWhatsAppComponentWithoutChangingItsClassName() {
        val directory = createTempDir(prefix = "openclone-dex-qualified-test-")
        val input = File(directory, "classes.dex")
        val output = File(directory, "rewritten.dex")
        try {
            val dexBuilder = DexBuilder(Opcodes.getDefault())
            val component = "com.whatsapp/com.whatsapp.registration.app.phonenumberentry.RegisterPhone"
            val implementation = MethodImplementationBuilder(1).apply {
                addInstruction(
                    BuilderInstruction21c(
                        Opcode.CONST_STRING,
                        0,
                        dexBuilder.internStringReference(component)
                    )
                )
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }.methodImplementation
            val method = dexBuilder.internMethod(
                "Lcom/whatsapp/Registration;",
                "launchFullyQualifiedRegistration",
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
                emptySet(),
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(FileDataStore(input))

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


    @Test
    fun rewritesSplitWhatsAppComponentPackageLiteralAndPreservesRelativeClassLiteral() {
        val directory = createTempDir(prefix = "openclone-dex-split-component-test-")
        val input = File(directory, "classes.dex")
        val output = File(directory, "rewritten.dex")
        try {
            val dexBuilder = DexBuilder(Opcodes.getDefault())
            val implementation = MethodImplementationBuilder(2).apply {
                // Apps can construct ComponentName(packageName, className) from two strings.
                addInstruction(BuilderInstruction21c(
                    Opcode.CONST_STRING, 0, dexBuilder.internStringReference("com.whatsapp")
                ))
                addInstruction(BuilderInstruction21c(
                    Opcode.CONST_STRING, 1,
                    dexBuilder.internStringReference(".registration.app.phonenumberentry.RegisterPhone")
                ))
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }.methodImplementation
            val method = dexBuilder.internMethod(
                "Lcom/whatsapp/Registration;",
                "launchSplitComponent",
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
                emptySet(),
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(FileDataStore(input))

            val result = DexPackageStringRewriter.rewrite(
                input = input,
                output = output,
                oldPackage = "com.whatsapp",
                newPackage = "com.whatsapp.clone"
            )

            assertEquals(1, result.replacements)
            val audit = DexPackageStringRewriter.auditComponentReferences(
                output, "com.whatsapp", "com.whatsapp.clone"
            )
            assertEquals(0, audit.oldPackageLiterals)
            assertEquals(0, audit.oldFlattenedComponentReferences)
            assertTrue(audit.clonePackageLiterals > 0)
            val rewrittenClass = DexFileFactory.loadDexFile(output, Opcodes.getDefault()).classes.single()
            assertEquals("Lcom/whatsapp/Registration;", rewrittenClass.type)
            val strings = rewrittenClass.methods
                .flatMap { it.implementation?.instructions?.toList().orEmpty() }
                .mapNotNull { instruction ->
                    (instruction as? ReferenceInstruction)?.reference as? StringReference
                }
                .map { it.string }
            assertTrue(strings.contains("com.whatsapp.clone"))
            assertTrue(strings.contains(".registration.app.phonenumberentry.RegisterPhone"))
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun auditReportsButDoesNotTreatStandaloneOriginalPackageLiteralAsFlattenedComponent() {
        val directory = createTempDir(prefix = "openclone-dex-audit-literal-only-")
        val input = File(directory, "literal-only.dex")
        try {
            val dexBuilder = DexBuilder(Opcodes.getDefault())
            val implementation = MethodImplementationBuilder(1).apply {
                addInstruction(BuilderInstruction21c(
                    Opcode.CONST_STRING, 0, dexBuilder.internStringReference("com.whatsapp")
                ))
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }.methodImplementation
            val method = dexBuilder.internMethod(
                "Lcom/example/Metadata;",
                "originalPackageName",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                emptySet(),
                emptySet(),
                implementation
            )
            dexBuilder.internClassDef(
                "Lcom/example/Metadata;",
                AccessFlags.PUBLIC.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                emptySet(),
                emptyList(),
                listOf(method)
            )
            dexBuilder.writeTo(FileDataStore(input))

            val audit = DexPackageStringRewriter.auditComponentReferences(
                input, "com.whatsapp", "com.whatsapp.clone"
            )

            assertEquals(1, audit.oldPackageLiterals)
            assertEquals(0, audit.oldFlattenedComponentReferences)
        } finally {
            directory.deleteRecursively()
        }
    }

}
