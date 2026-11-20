package org.openclone.app.virtualspace

import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class VirtualSpaceStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun createsAndReloadsAnIsolatedInstance() {
        val source = temporaryFolder.newFile("source.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "store"))

        val created = store.createInstance("com.example.notes", "Notes", source)
        val reloaded = VirtualSpaceStore(File(temporaryFolder.root, "store")).findInstance(created.id)

        assertNotNull(reloaded)
        assertEquals("com.example.notes", reloaded!!.originalPackage)
        assertEquals("Notes", reloaded.label)
        assertArrayEquals(source.readBytes(), reloaded.apkFile.readBytes())
        assertTrue(reloaded.privateDataDirectory.isDirectory)
        assertEquals(1, store.listInstances().size)
    }

    @Test fun storesAndRestoresSplitApkSets() {
        val base = temporaryFolder.newFile("base.apk").apply { writeText("base") }
        val split = temporaryFolder.newFile("split.apk").apply { writeText("split") }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "split-store"))

        val instance = store.createInstance("com.example.bundle", "Bundle", listOf(base, split))
        val restored = store.findInstance(instance.id)

        assertNotNull(restored)
        assertEquals(2, restored!!.apkFiles.size)
        assertEquals("base", restored.apkFiles[0].readText())
        assertEquals("split", restored.apkFiles[1].readText())
    }

    @Test fun persistsGuestComponentDeclarations() {
        val source = temporaryFolder.newFile("component.apk").apply { writeText("apk") }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "component-store"))
        val declarations = listOf(
            GuestComponentDeclaration(
                className = "com.example.notes.MainActivity",
                kind = GuestComponentDeclaration.Kind.ACTIVITY,
                exported = true,
                enabled = true,
                permission = null,
                processName = "com.example.notes"
            ),
            GuestComponentDeclaration(
                className = "com.example.notes.SyncService",
                kind = GuestComponentDeclaration.Kind.SERVICE,
                exported = false,
                enabled = true,
                permission = "com.example.notes.permission.SYNC",
                processName = ":sync"
            ),
            GuestComponentDeclaration(
                className = "com.example.notes.NotesProvider",
                kind = GuestComponentDeclaration.Kind.PROVIDER,
                exported = false,
                enabled = true,
                permission = null,
                processName = null,
                authority = "com.example.notes.provider"
            )
        )

        val created = store.createInstance("com.example.notes", "Notes", listOf(source), declarations)
        val restored = VirtualSpaceStore(File(temporaryFolder.root, "component-store"))
            .findInstance(created.id)

        assertNotNull(restored)
        assertEquals(declarations, restored!!.components)
    }

    @Test fun readsLegacyVersionTwoInstancesWithoutComponentCatalog() {
        val source = temporaryFolder.newFile("legacy.apk").apply { writeText("apk") }
        val root = File(temporaryFolder.root, "legacy-store")
        val store = VirtualSpaceStore(root)
        val created = store.createInstance("com.example.legacy", "Legacy", source)
        val metadataFile = File(created.privateDataDirectory.parentFile, "instance.properties")
        val properties = Properties().apply {
            metadataFile.inputStream().use { load(it) }
            setProperty("formatVersion", "2")
            remove("componentCount")
        }
        metadataFile.outputStream().use { properties.store(it, "legacy v2 fixture") }

        val restored = store.findInstance(created.id)
        assertNotNull(restored)
        assertTrue(restored!!.components.isEmpty())
        assertEquals("com.example.legacy", restored.originalPackage)
    }

    @Test fun rejectsInvalidGuestComponentClassNames() {
        val source = temporaryFolder.newFile("invalid-component.apk").apply { writeText("apk") }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "invalid-component-store"))
        val invalid = GuestComponentDeclaration(
            className = "../outside",
            kind = GuestComponentDeclaration.Kind.ACTIVITY,
            exported = true,
            enabled = true,
            permission = null,
            processName = null
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.createInstance("com.example.invalid", "Invalid", listOf(source), listOf(invalid))
        }
        assertTrue(store.listInstances().isEmpty())
    }

    @Test fun deletingAnInstanceDoesNotTouchItsSourceApk() {
        val source = temporaryFolder.newFile("original.apk").apply { writeText("original") }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "store"))
        val created = store.createInstance("com.example.reader", "Reader", source)

        assertTrue(store.deleteInstance(created.id))
        assertFalse(store.deleteInstance(created.id))
        assertTrue(source.exists())
        assertEquals("original", source.readText())
        assertTrue(store.listInstances().isEmpty())
    }

    @Test fun rejectsInvalidPackageNamesAndInstanceIds() {
        val source = temporaryFolder.newFile("source.apk").apply { writeText("apk") }
        val store = VirtualSpaceStore(File(temporaryFolder.root, "store"))

        assertThrows(IllegalArgumentException::class.java) {
            store.createInstance("../outside", "Bad", source)
        }
        assertNull(store.findInstance("../outside"))
        assertThrows(IllegalArgumentException::class.java) { store.deleteInstance("../outside") }
    }
}
