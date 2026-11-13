package org.openclone.app

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.openclone.app.clone.ApkCloneEngine
import org.openclone.app.clone.SigningKeys
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCompatibilityTest {
    @Test
    fun mainActivityStartsOnTheDeviceApiLevel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("org.openclone.app", activity.packageName)
                assertNotNull(activity.window)
            }
        }
    }

    @Test
    fun androidKeystoreProviderIsAvailable() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        assertFalse(keyStore.containsAlias("openclone-compatibility-probe"))
    }

    @Test
    fun signingIdentityIsEncryptedAndSupportsPasswordProtectedBackupRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keys = SigningKeys.get(context)
        val previousAlias = keys.activeAlias
        val alias = "CI-" + UUID.randomUUID().toString()
        val restoredAlias = "$alias-Restored"
        val password = "openclone-ci-password".toCharArray()
        val wrongPassword = "incorrect-password".toCharArray()
        try {
            keys.generate(alias, "OpenClone CI")
            val fingerprint = checkNotNull(keys.custom).fingerprint
            val encryptedStorage = File(context.filesDir, "signing/keys.bin").readBytes()
            assertFalse(String(encryptedStorage, Charsets.ISO_8859_1).contains(alias))

            val backup = ByteArrayOutputStream()
            keys.export(alias, backup, password)
            keys.import(ByteArrayInputStream(backup.toByteArray()), password, "", restoredAlias)
            assertTrue(keys.identityAliases.contains(alias))
            assertTrue(keys.identityAliases.contains(restoredAlias))
            assertEquals(restoredAlias, keys.activeAlias)
            assertEquals(fingerprint, keys.matchingFingerprint(fingerprint)?.fingerprint)
            assertFalse(
                runCatching {
                    keys.import(
                        ByteArrayInputStream(backup.toByteArray()),
                        wrongPassword,
                        "",
                        "$alias-WrongPassword"
                    )
                }.isSuccess
            )
        } finally {
            password.fill('\u0000')
            wrongPassword.fill('\u0000')
            keys.identityAliases.filter { it == alias || it == restoredAlias }.forEach(keys::delete)
            previousAlias?.takeIf { it in keys.identityAliases }?.let(keys::select)
        }
    }

    @Test
    fun applicationContextUsesTheExpectedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.openclone.app", context.packageName)
    }

    @Test
    fun rebuildsAndSignsARealInstalledApkWithNewPackageIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceInfo = context.applicationInfo
        val sourceApk = File(sourceInfo.sourceDir)
        val clonePackage = "org.openclone.app.ci" + UUID.randomUUID().toString().replace("-", "").take(10)
        val outputDirectory = File(context.cacheDir, "clone-engine-ci/$clonePackage")
        try {
            val result = ApkCloneEngine(context).build(
                sourceApks = listOf(sourceApk),
                originalPackage = context.packageName,
                newPackage = clonePackage,
                newLabel = "OpenClone CI Clone",
                outputDirectory = outputDirectory,
                markIcon = false,
                rewriteDexPackageStrings = false
            )

            assertEquals(context.packageName, result.originalPackage)
            assertEquals(clonePackage, result.clonePackage)
            assertEquals(1, result.apks.size)
            val rebuiltApk = result.apks.single()
            assertTrue(rebuiltApk.isFile && rebuiltApk.length() > 0L)

            val packageManager = context.packageManager
            val parsed = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageArchiveInfo(
                    rebuiltApk.absolutePath,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(rebuiltApk.absolutePath, 0)
            }
            assertNotNull(parsed)
            assertEquals(clonePackage, parsed?.packageName)

            ZipFile(rebuiltApk).use { zip ->
                val metadata = checkNotNull(zip.getEntry("META-INF/openclone.properties"))
                val properties = java.util.Properties().apply {
                    zip.getInputStream(metadata).use(::load)
                }
                assertEquals(context.packageName, properties.getProperty("originalPackage"))
                assertEquals(clonePackage, properties.getProperty("clonePackage"))
                assertTrue(properties.getProperty("signingFingerprint").isNotBlank())
            }
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

}
