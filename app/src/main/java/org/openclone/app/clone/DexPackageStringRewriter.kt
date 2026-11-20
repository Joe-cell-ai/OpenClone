package org.openclone.app.clone

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction31c
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.rewriter.Rewriters

/**
 * Rewrites package-name string literals in DEX files without renaming classes or methods.
 * Class/type descriptors are deliberately left untouched.
 */
object DexPackageStringRewriter {
    data class Result(val replacements: Int)

    private const val PROGRESS_INTERVAL = 20_000L

    fun rewrite(
        input: File,
        output: File,
        oldPackage: String,
        newPackage: String,
        ensureActive: () -> Unit = {},
        /** Called every [PROGRESS_INTERVAL] visited instructions with the running total. */
        onInstructions: (Long) -> Unit = {}
    ): Result {
        require(input.isFile && input.canRead()) { "DEX input is missing or unreadable." }
        require(oldPackage.isNotBlank() && newPackage.isNotBlank() && oldPackage != newPackage) {
            "A distinct source and target package ID is required for DEX rewriting."
        }
        val replacements = AtomicInteger()
        val visited = java.util.concurrent.atomic.AtomicLong()
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> {
                val delegate = super.getInstructionRewriter(rewriters)
                return object : Rewriter<Instruction> {
                    override fun rewrite(value: Instruction): Instruction {
                        ensureActive()
                        val count = visited.incrementAndGet()
                        if (count % PROGRESS_INTERVAL == 0L) onInstructions(count)

                        // dexlib2's default InstructionRewriter intentionally does not rewrite
                        // STRING references. Inspect the original instruction before delegating.
                        val reference = (value as? org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                            ?.reference as? org.jf.dexlib2.iface.reference.StringReference
                        if (reference != null) {
                            val changed = replacePackageIdentityToken(reference.string, oldPackage, newPackage)
                            if (changed != reference.string) {
                                val immutableReference =
                                    org.jf.dexlib2.immutable.reference.ImmutableStringReference(changed)
                                val rewritten = when (value) {
                                    is Instruction21c -> ImmutableInstruction21c(
                                        value.opcode, value.registerA, immutableReference
                                    )
                                    is Instruction31c -> ImmutableInstruction31c(
                                        value.opcode, value.registerA, immutableReference
                                    )
                                    else -> null
                                }
                                if (rewritten != null) {
                                    replacements.incrementAndGet()
                                    return rewritten
                                }
                            }
                        }
                        return delegate.rewrite(value)
                    }
                }
            }

            override fun getEncodedValueRewriter(rewriters: Rewriters): Rewriter<EncodedValue> {
                val delegate = super.getEncodedValueRewriter(rewriters)
                return object : Rewriter<EncodedValue> {
                    override fun rewrite(value: EncodedValue): EncodedValue {
                        ensureActive()
                        if (value is StringEncodedValue) {
                            val changed = replacePackageIdentityToken(value.value, oldPackage, newPackage)
                            if (changed != value.value) {
                                replacements.incrementAndGet()
                                return ImmutableStringEncodedValue(changed)
                            }
                        }
                        return delegate.rewrite(value)
                    }
                }
            }
        })
        ensureActive()
        val dex = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        val rewritten = rewriter.dexFileRewriter.rewrite(dex)
        output.parentFile?.mkdirs()
        ensureActive()
        DexFileFactory.writeDexFile(output.absolutePath, rewritten)
        ensureActive()
        require(output.isFile && output.length() > 0L) { "DEX rewriting produced an empty output file." }
        DexFileFactory.loadDexFile(output, Opcodes.getDefault())
        return Result(replacements.get())
    }

    /**
     * Rewrites only an app-package identity, not a Java/Kotlin class name that merely starts
     * with the same package prefix. A flattened component such as
     * "com.example.app/.RegistrationActivity" is expanded to retain the original DEX class
     * name while moving the component package to the installed clone package.
     */
    internal fun replacePackageIdentityToken(value: String, oldPackage: String, newPackage: String): String {
        if (value == oldPackage) return newPackage
        val marker = "$oldPackage/"
        var searchFrom = 0
        while (true) {
            val index = value.indexOf(marker, searchFrom)
            if (index < 0) return value
            val hasPackageBoundary = index == 0 || !isPackageCharacter(value[index - 1])
            if (!hasPackageBoundary) {
                searchFrom = index + marker.length
                continue
            }
            val classPart = value.substring(index + marker.length)
            if (classPart.isBlank()) return value
            val qualifiedClass = if (classPart.startsWith(".")) "$oldPackage$classPart" else classPart
            return value.substring(0, index) + newPackage + "/" + qualifiedClass
        }
    }

    internal fun replacePackageToken(value: String, oldPackage: String, newPackage: String): String {
        val forms = listOf(oldPackage, oldPackage.replace('.', '/')).distinct()
        var result = value
        for (old in forms) {
            var from = 0
            while (true) {
                val index = result.indexOf(old, from)
                if (index < 0) break
                val end = index + old.length
                val leftBoundary = index == 0 || !isPackageCharacter(result[index - 1])
                val rightBoundary = end == result.length || !result[end].isLetterOrDigit() && result[end] != '_'
                if (leftBoundary && rightBoundary) {
                    val replacement = if ('/' in old) newPackage.replace('.', '/') else newPackage
                    result = result.replaceRange(index, end, replacement)
                    from = index + replacement.length
                } else {
                    from = end
                }
            }
        }
        return result
    }

    private fun isPackageCharacter(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '.'
}
