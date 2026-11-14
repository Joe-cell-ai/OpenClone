package org.openclone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloneProxyAssignmentRepositoryTest {
    @Test
    fun assignmentAcceptsClonePackageAndSavedProfileName() {
        val assignment = CloneProxyAssignment("com.example.clone", "Work proxy")
        CloneProxyAssignmentRepository.validate(assignment)
        assertEquals("com.example.clone", assignment.clonePackage)
    }

    @Test
    fun assignmentRejectsInvalidPackageNames() {
        assertThrows(IllegalArgumentException::class.java) {
            CloneProxyAssignmentRepository.validate(CloneProxyAssignment("not a package", "Proxy"))
        }
    }

    @Test
    fun assignmentRejectsBlankProfileNames() {
        assertThrows(IllegalArgumentException::class.java) {
            CloneProxyAssignmentRepository.validate(CloneProxyAssignment("com.example.clone", " "))
        }
    }
}
