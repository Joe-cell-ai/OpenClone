package org.openclone.app.clone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

data class CloneBuildResult(
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val apks: List<File>,
    val iconBadgeApplied: Boolean = false,
    val customIconApplied: Boolean = false,
    val obbFiles: List<File> = emptyList()
)

/**
 * Rebuilds an APK set with a new Android package identity and signs every member with
 * OpenClone's private, per-install signing identity.
 *
 * The manifest/resource rewrite is adapted from APK Toolbox (GPL-3.0-only).
 * Optional DEX package-string rewriting is experimental; it does not rename classes or defeat app integrity checks.
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
        onProgress: (Int) -> Unit = {},
        markIcon: Boolean = true,
        allowUpdate: Boolean = false,
        ensureActive: () -> Unit = {},
        badgePosition: BadgePosition = BadgePosition.BOTTOM_RIGHT,
        customIconFile: File? = null,
        rewriteDexPackageStrings: Boolean = true,
        removedPermissions: Set<String> = emptySet(),
        versionNameOverride: String? = null,
        versionCodeOverride: Int? = null
    ): CloneBuildResult {
        val paths = listOfNotNull(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()
        val sourceApks = paths.map(::File)
        return build(sourceApks, originalPackage, newPackage, newLabel, outputDirectory, onProgress, applicationInfo.takeIf { markIcon || customIconFile != null }, allowUpdate, ensureActive, badgePosition, markIcon, customIconFile, rewriteDexPackageStrings, removedPermissions, versionNameOverride, versionCodeOverride)
    }

    @Suppress("DEPRECATION")
    fun build(
        sourceApks: List<File>,
        originalPackage: String,
        newPackage: String,
        newLabel: String,
        outputDirectory: File,
        onProgress: (Int) -> Unit = {},
        iconSource: android.content.pm.ApplicationInfo? = null,
        allowUpdate: Boolean = false,
        ensureActive: () -> Unit = {},
        badgePosition: BadgePosition = BadgePosition.BOTTOM_RIGHT,
        markIcon: Boolean = true,
        customIconFile: File? = null,
        rewriteDexPackageStrings: Boolean = true,
        removedPermissions: Set<String> = emptySet(),
        versionNameOverride: String? = null,
        versionCodeOverride: Int? = null
    ): CloneBuildResult {
        require(sourceApks.isNotEmpty()) { "No APK files were supplied." }
        require(sourceApks.all { it.isFile && it.canRead() }) { "One or more source APK files cannot be read." }
        // Fail before touching an existing output directory when the current heap is too small.
        CloneBuildPreflight.requireSafe(CloneBuildPreflight.inspect(sourceApks))
        require(PACKAGE_NAME.matches(newPackage)) {
            "Enter a valid package ID, for example com.example.myclone."
        }
        require(newPackage != originalPackage) { "The clone package ID must differ from the original." }
        require(newLabel.isNotBlank() && newLabel.length <= 100) {
            "The app name must contain 1–100 characters."
        }
        val selectedSigningKey = signingKeys.active
        val existing = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(newPackage, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                packageManager.getApplicationInfo(newPackage, 0)
            }
        }.getOrNull()
        val signingKey = if (existing != null) {
            val installedFingerprint = installedSigningFingerprint(newPackage)
            val matchingKey = installedFingerprint?.let(signingKeys::matchingFingerprint)
            require(
                allowUpdate && matchingKey != null &&
                    isMatchingOpenClone(existing, originalPackage, newPackage, matchingKey.fingerprint)
            ) {
                "An app with package ID $newPackage is already installed. OpenClone can only update clones signed by one of this device's saved identities."
            }
            matchingKey!!
        } else {
            selectedSigningKey
        }

        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Could not create the clone output directory." }
        val sourceVersionCode = runCatching {
            val archive = packageManager.getPackageArchiveInfo(sourceApks.first().absolutePath, 0)
                ?: error("Could not read the source APK version.")
            if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        }.getOrDefault(0L)
        val unsigned = File(outputDirectory, "unsigned.tmp")
        val outputs = mutableListOf<File>()
        try {
            var iconBadgeApplied = false
            var customIconApplied = false
            sourceApks.forEachIndexed { index, source ->
                ensureActive()
                val replacements = if (index == 0 && iconSource != null && (markIcon || customIconFile != null)) {
                    runCatching {
                        IconBadger(appContext).replacements(iconSource, newPackage, badgePosition, customIconFile, markIcon)
                    }.getOrDefault(emptyMap()).also { entries ->
                        iconBadgeApplied = markIcon && entries.isNotEmpty()
                        customIconApplied = customIconFile?.isFile == true && entries.keys.any { it.endsWith(".png", ignoreCase = true) }
                    }
                } else emptyMap()
                val metadataEntry: (() -> Pair<String, ByteArray>)? = if (index == 0) {
                    val metadataText = listOf(
                        "format=1",
                        "originalPackage=$originalPackage",
                        "originalVersionCode=$sourceVersionCode",
                        "clonePackage=$newPackage",
                        "cloneLabelBase64=${java.util.Base64.getEncoder().encodeToString(newLabel.toByteArray(Charsets.UTF_8))}",
                        "createdAt=${System.currentTimeMillis()}",
                        "signingFingerprint=${signingKey.fingerprint}"
                    ).joinToString(separator = "\n", postfix = "\n")
                    ({
                        "META-INF/openclone.properties" to metadataText.toByteArray(Charsets.UTF_8)
                    })
                } else null
                val patcher = ManifestPatcher(
                    newPackage = newPackage,
                    newLabel = newLabel.takeIf { index == 0 },
                    isSplitApk = index > 0,
                    removedPermissions = removedPermissions,
                    versionNameOverride = versionNameOverride,
                    versionCodeOverride = versionCodeOverride
                )
                ensureActive()
                ApkRebuilder.rebuild(
                    source = source,
                    target = unsigned,
                    patcher = patcher,
                    replacements = replacements,
                    extraEntry = metadataEntry,
                    onStep = ensureActive,
                    onProgress = { localProgress ->
                        onProgress(((index * 100) + localProgress) / sourceApks.size)
                    },
                    dexPackageRewrite = if (rewriteDexPackageStrings) originalPackage.takeIf { it != newPackage }?.let { it to newPackage } else null
                )

                ensureActive()
                val output = File(outputDirectory, if (index == 0) "base.apk" else "split_$index.apk")
                ApkSigner.Builder(listOf(signingKey.signerConfig))
                    .setInputApk(unsigned)
                    .setOutputApk(output)
                    .setCreatedBy("OpenClone")
                    .build()
                    .sign()

                ensureActive()
                val verification = ApkVerifier.Builder(output).build().verify()
                check(verification.isVerified) {
                    "The rebuilt APK did not pass signature verification: ${verification.errors.joinToString()}"
                }
                outputs += output
                onProgress(((index + 1) * 100 / sourceApks.size).coerceIn(0, 100))
            }
            return CloneBuildResult(originalPackage, newPackage, newLabel, outputs.toList(), iconBadgeApplied, customIconApplied)
        } catch (error: Exception) {
            outputDirectory.deleteRecursively()
            throw error
        } finally {
            unsigned.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun isMatchingOpenClone(
        installedInfo: android.content.pm.ApplicationInfo,
        originalPackage: String,
        clonePackage: String,
        fingerprint: String
    ): Boolean {
        val path = installedInfo.sourceDir ?: return false
        val metadata = runCatching {
            ZipFile(path).use { zip ->
                val entry = zip.getEntry("META-INF/openclone.properties") ?: return@use null
                Properties().apply { zip.getInputStream(entry).use { load(it) } }
            }
        }.getOrNull() ?: return false
        if (metadata.getProperty("originalPackage") != originalPackage ||
            metadata.getProperty("clonePackage") != clonePackage ||
            metadata.getProperty("signingFingerprint") != fingerprint) return false
        return installedSigningFingerprint(clonePackage) == fingerprint
    }

    @Suppress("DEPRECATION")
    private fun installedSigningFingerprint(packageName: String): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
        val certificate = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
