package org.openclone.app.clone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

data class CloneBuildResult(
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val apks: List<File>
)

/**
 * Rebuilds an APK set with a new Android package identity and signs every member with
 * OpenClone's private, per-install signing identity.
 *
 * The manifest/resource rewrite is adapted from APK Toolbox (GPL-3.0-only).
 * This intentionally does not patch arbitrary DEX strings or defeat app integrity checks.
 */
class ApkCloneEngine(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val signingKeys = SigningKeys.get(appContext)

    fun buildInstalled(
        applicationInfo: android.content.pm.ApplicationInfo,
        originalPackage: String,
        newPackage: String,
        newLabel: String,
        outputDirectory: File,
        onProgress: (Int) -> Unit = {}
    ): CloneBuildResult {
        val paths = listOfNotNull(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()
        val sourceApks = paths.map(::File)
        return build(sourceApks, originalPackage, newPackage, newLabel, outputDirectory, onProgress)
    }

    @Suppress("DEPRECATION")
    fun build(
        sourceApks: List<File>,
        originalPackage: String,
        newPackage: String,
        newLabel: String,
        outputDirectory: File,
        onProgress: (Int) -> Unit = {}
    ): CloneBuildResult {
        require(sourceApks.isNotEmpty()) { "No APK files were supplied." }
        require(sourceApks.all { it.isFile && it.canRead() }) { "One or more source APK files cannot be read." }
        require(PACKAGE_NAME.matches(newPackage)) {
            "Enter a valid package ID, for example com.example.myclone."
        }
        require(newPackage != originalPackage) { "The clone package ID must differ from the original." }
        require(newLabel.isNotBlank() && newLabel.length <= 100) {
            "The app name must contain 1–100 characters."
        }
        val existing = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(newPackage, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                packageManager.getApplicationInfo(newPackage, 0)
            }
        }.getOrNull()
        require(existing == null) { "An app with package ID $newPackage is already installed." }

        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Could not create the clone output directory." }
        val unsigned = File(outputDirectory, "unsigned.tmp")
        val outputs = mutableListOf<File>()
        try {
            val signingKey = signingKeys.active
            sourceApks.forEachIndexed { index, source ->
                val patcher = ManifestPatcher(
                    newPackage = newPackage,
                    newLabel = newLabel.takeIf { index == 0 },
                    isSplitApk = index > 0
                )
                ApkRebuilder.rebuild(
                    source = source,
                    target = unsigned,
                    patcher = patcher,
                    replacements = emptyMap(),
                    extraEntry = null
                )

                val output = File(outputDirectory, if (index == 0) "base.apk" else "split_$index.apk")
                ApkSigner.Builder(listOf(signingKey.signerConfig))
                    .setInputApk(unsigned)
                    .setOutputApk(output)
                    .setCreatedBy("OpenClone")
                    .build()
                    .sign()

                val verification = ApkVerifier.Builder(output).build().verify()
                check(verification.isVerified) {
                    "The rebuilt APK did not pass signature verification: ${verification.errors.joinToString()}"
                }
                outputs += output
                onProgress(((index + 1) * 100 / sourceApks.size).coerceIn(0, 100))
            }
            return CloneBuildResult(originalPackage, newPackage, newLabel, outputs.toList())
        } catch (error: Exception) {
            outputDirectory.deleteRecursively()
            throw error
        } finally {
            unsigned.delete()
        }
    }

    companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
