package org.openclone.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledObbDiscoveryTest {
    @Test
    fun discoversOnlyDirectObbFilesAndSortsThemDeterministically() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            val appDirectory = File(root, "com.example.game").apply { mkdirs() }
            File(appDirectory, "patch.4.com.example.game.obb").writeText("patch")
            File(appDirectory, "main.4.com.example.game.obb").writeText("main")
            File(appDirectory, "notes.txt").writeText("ignore")
            File(appDirectory, "nested").apply { mkdirs() }
                .resolve("nested.obb").writeText("ignore")

            val result = InstalledObbDiscovery.inspect(
                packageName = "com.example.game",
                sdkInt = 28,
                hasReadPermission = true,
                obbRoot = root,
                storageReadable = true
            )

            assertEquals(InstalledObbDiscovery.Status.FOUND, result.status)
            assertEquals(
                listOf("main.4.com.example.game.obb", "patch.4.com.example.game.obb"),
                result.files.map { it.name }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiresExplicitReadPermissionBeforeLookingAtTheDirectory() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            File(root, "com.example.game").apply { mkdirs() }
                .resolve("main.1.com.example.game.obb").writeText("obb")

            val result = InstalledObbDiscovery.inspect(
                packageName = "com.example.game",
                sdkInt = 28,
                hasReadPermission = false,
                obbRoot = root,
                storageReadable = true
            )

            assertEquals(InstalledObbDiscovery.Status.PERMISSION_REQUIRED, result.status)
            assertTrue(result.files.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsScopedStorageRestrictionOnAndroidTenAndLater() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            File(root, "com.example.game").apply { mkdirs() }
                .resolve("main.1.com.example.game.obb").writeText("obb")

            val result = InstalledObbDiscovery.inspect(
                packageName = "com.example.game",
                sdkInt = 35,
                hasReadPermission = true,
                obbRoot = root,
                storageReadable = true
            )

            assertEquals(InstalledObbDiscovery.Status.SCOPED_STORAGE_RESTRICTED, result.status)
            assertTrue(result.files.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsMissingExpansionDataWithoutFailingTheCloneFlow() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            val result = InstalledObbDiscovery.inspect(
                packageName = "com.example.game",
                sdkInt = 28,
                hasReadPermission = true,
                obbRoot = root,
                storageReadable = true
            )
            assertEquals(InstalledObbDiscovery.Status.NOT_FOUND, result.status)
            assertTrue(result.files.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsUnavailableSharedStorage() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            val result = InstalledObbDiscovery.inspect(
                packageName = "com.example.game",
                sdkInt = 28,
                hasReadPermission = true,
                obbRoot = root,
                storageReadable = false
            )
            assertEquals(InstalledObbDiscovery.Status.STORAGE_UNAVAILABLE, result.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPackageNamesThatCouldEscapeTheObbRoot() {
        val root = Files.createTempDirectory("openclone-obb").toFile()
        try {
            val result = InstalledObbDiscovery.inspect(
                packageName = "../other.app",
                sdkInt = 28,
                hasReadPermission = true,
                obbRoot = root,
                storageReadable = true
            )
            assertEquals(InstalledObbDiscovery.Status.INVALID_PACKAGE, result.status)
            assertTrue(result.files.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
