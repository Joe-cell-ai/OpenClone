package org.openclone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloneProfileTest {
    @Test
    fun validProfileConfigurationIsAccepted() {
        CloneProfile.validate(
            name = "Work profile",
            originalPackage = "com.example.app",
            clonePackage = "com.example.app.work",
            cloneLabel = "Example Work"
        )
        assertEquals(1, 1)
    }

    @Test
    fun identicalOriginalAndClonePackagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CloneProfile.validate(
                name = "Work",
                originalPackage = "com.example.app",
                clonePackage = "com.example.app",
                cloneLabel = "Example Work"
            )
        }
    }

    @Test
    fun malformedPackageIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CloneProfile.validate(
                name = "Work",
                originalPackage = "not a package",
                clonePackage = "com.example.app.work",
                cloneLabel = "Example Work"
            )
        }
    }

    @Test
    fun blankProfileNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CloneProfile.validate(
                name = " ",
                originalPackage = "com.example.app",
                clonePackage = "com.example.app.work",
                cloneLabel = "Example Work"
            )
        }
    }
}
