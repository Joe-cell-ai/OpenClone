package org.openclone.app.clone

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters

/**
 * Rewrites package-name string literals in DEX files without renaming classes or methods.
 * Class/type descriptors are deliberately left untouched.
 */
object DexPackageStringRewriter {
    data class Result(val replacements: Int)

    fun rewrite(input: File, output: File, oldPackage: String, newPackage: String): Result {
        require(input.isFile && input.canRead()) { "DEX input is missing or unreadable." }
        require(oldPackage.isNotBlank() && newPackage.isNotBlank() && oldPackage != newPackage) {
            "A distinct source and target package ID is required for DEX rewriting."
        }
        val replacements = AtomicInteger()
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getStringRewriter(rewriters: Rewriters): Rewriter<StringReference> =
                object : Rewriter<StringReference> {
                    override fun rewrite(value: StringReference): StringReference {
                        val rewritten = replacePackageToken(value.string, oldPackage, newPackage)
                        if (rewritten == value.string) return value
                        replacements.incrementAndGet()
                        return ImmutableStringReference(rewritten)
                    }
                }
        })
        val dex = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        val rewritten = rewriter.rewriteDexFile(dex)
        output.parentFile?.mkdirs()
        DexFileFactory.writeDexFile(output.absolutePath, rewritten)
        require(output.isFile && output.length() > 0L) { "DEX rewriting produced an empty output file." }
        return Result(replacements.get())
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
                val rightBoundary = end == result.length || !isPackageCharacter(result[end])
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
