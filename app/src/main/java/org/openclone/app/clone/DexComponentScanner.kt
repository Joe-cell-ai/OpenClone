package org.openclone.app.clone

import java.io.File
import java.util.zip.ZipFile
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference

/**
 * Read-only diagnostic scan of how an APK's DEX methods refer to their own package.
 *
 * This is deliberately a heuristic, not data-flow analysis: literals and APIs found in the
 * same method are grouped together. DYNAMIC findings must be inspected manually.
 */
object DexComponentScanner {
    enum class Shape {
        FLATTENED,
        SEPARATE_LITERALS,
        DYNAMIC,
        PACKAGE_ONLY
    }

    data class Finding(
        val method: String,
        val shape: Shape,
        val literals: List<String>,
        val componentApis: List<String>
    )

    private val componentApis = setOf(
        "ComponentName-><init>",
        "Intent->setClassName",
        "Intent->setComponent",
        "Intent->setPackage",
        "Intent-><init>",
        "PackageManager->getLaunchIntentForPackage",
        "ComponentName->unflattenFromString",
        "Intent->parseUri",
        "Class->forName",
        "PendingIntent->getActivity"
    )

    private val stringBuildingApis = setOf(
        "StringBuilder->append",
        "String->concat",
        "String->format",
        "String->valueOf"
    )

    fun scanApk(apk: File, oldPackage: String): List<Finding> {
        require(apk.isFile && apk.canRead()) { "APK is missing or unreadable: $apk" }
        require(oldPackage.isNotBlank()) { "Original package ID must not be blank." }

        val findings = mutableListOf<Finding>()
        val temp = File.createTempFile("openclone-scan-", ".dex")
        try {
            ZipFile(apk).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .toList()
                for (entry in dexEntries) {
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    findings += scan(temp, oldPackage).map {
                        it.copy(method = "${entry.name}:${it.method}")
                    }
                }
            }
        } finally {
            temp.delete()
        }
        return findings
    }

    fun scan(dexFile: File, oldPackage: String): List<Finding> {
        require(dexFile.isFile && dexFile.canRead()) { "DEX file is missing or unreadable: $dexFile" }
        require(oldPackage.isNotBlank()) { "Original package ID must not be blank." }

        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val findings = mutableListOf<Finding>()
        for (classDef in dex.classes) {
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                val literals = mutableListOf<String>()
                val apis = linkedSetOf<String>()
                for (instruction in instructions) {
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    when (reference) {
                        is StringReference -> literals += reference.string
                        is MethodReference -> {
                            val owner = reference.definingClass.substringAfterLast('/').trimEnd(';')
                            apis += "$owner->${reference.name}"
                        }
                    }
                }

                val relevant = literals.filter {
                    it == oldPackage ||
                        it.startsWith("$oldPackage/") ||
                        it.startsWith("$oldPackage.") ||
                        it.startsWith("/.") ||
                        (it.startsWith(".") && it.length > 1)
                }
                if (relevant.none { it == oldPackage || it.startsWith("$oldPackage/") }) continue

                val foundComponentApis = apis.filter { it in componentApis }
                val hasStringBuilding = apis.any { it in stringBuildingApis }
                val shape = when {
                    relevant.any { it.startsWith("$oldPackage/") } -> Shape.FLATTENED
                    relevant.any { it.startsWith("/.") || it.startsWith(".") } || hasStringBuilding -> Shape.DYNAMIC
                    relevant.any { it.startsWith("$oldPackage.") } -> Shape.SEPARATE_LITERALS
                    else -> Shape.PACKAGE_ONLY
                }
                findings += Finding(
                    method = "${classDef.type}->${method.name}",
                    shape = shape,
                    literals = relevant.distinct(),
                    componentApis = foundComponentApis + apis.filter { it in stringBuildingApis }
                )
            }
        }
        return findings
    }

    /** Command line: DexComponentScanner <apk-or-dex> <original-package> */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: DexComponentScanner <apk-or-dex> <original-package>" }
        val file = File(args[0])
        val findings = if (file.extension.equals("dex", ignoreCase = true)) {
            scan(file, args[1])
        } else {
            scanApk(file, args[1])
        }
        findings.groupBy { it.shape }.forEach { (shape, items) ->
            println("== $shape (${items.size})")
            items.forEach {
                println("  ${it.method}\n    literals=${it.literals}\n    apis=${it.componentApis}")
            }
        }
        if (findings.isEmpty()) println("No method pairs the package literal with a component API.")
    }
}
