package org.openclone.app.virtualspace

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestActivityResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun instance(): VirtualAppInstance {
        val apk = temporaryFolder.newFile("base.apk").apply { writeText("test") }
        return VirtualSpaceStore(File(temporaryFolder.root, "store")).createInstance(
            "com.example.guest",
            "Guest",
            apk,
            listOf(
                GuestComponentDeclaration(
                    "com.example.guest.HomeActivity",
                    GuestComponentDeclaration.Kind.ACTIVITY,
                    exported = true,
                    enabled = true,
                    permission = null,
                    processName = null
                ),
                GuestComponentDeclaration(
                    "com.example.guest.SettingsActivity",
                    GuestComponentDeclaration.Kind.ACTIVITY,
                    exported = false,
                    enabled = true,
                    permission = null,
                    processName = null
                ),
                GuestComponentDeclaration(
                    "com.example.guest.DisabledActivity",
                    GuestComponentDeclaration.Kind.ACTIVITY,
                    exported = true,
                    enabled = false,
                    permission = null,
                    processName = null
                )
            )
        )
    }

    @Test fun resolvesExportedExplicitActivityFromExternalCaller() {
        val guest = instance()
        val resolved = GuestActivityResolver().resolveExplicit(
            guest, "com.example.guest", ".HomeActivity", "org.openclone.app"
        )
        assertNotNull(resolved)
        assertEquals("com.example.guest.HomeActivity", resolved!!.className)
        assertTrue(resolved.exported)
    }

    @Test fun permitsPrivateActivityOnlyForSameGuestPackage() {
        val guest = instance()
        val resolver = GuestActivityResolver()
        assertNull(resolver.resolveExplicit(
            guest, guest.originalPackage, "SettingsActivity", "org.openclone.app"
        ))
        assertNotNull(resolver.resolveExplicit(
            guest, guest.originalPackage, "SettingsActivity", guest.originalPackage
        ))
    }

    @Test fun rejectsDisabledUnknownAndForeignComponents() {
        val guest = instance()
        val resolver = GuestActivityResolver()
        assertNull(resolver.resolveExplicit(
            guest, guest.originalPackage, "DisabledActivity", guest.originalPackage
        ))
        assertNull(resolver.resolveExplicit(
            guest, guest.originalPackage, "MissingActivity", guest.originalPackage
        ))
        assertNull(resolver.resolveExplicit(
            guest, "com.other.app", "HomeActivity", guest.originalPackage
        ))
        assertNull(resolver.resolveExplicit(
            guest, guest.originalPackage, "com.other.app.FakeActivity", guest.originalPackage
        ))
    }
}
