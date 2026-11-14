package org.openclone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneCompatibilityAnalyzerTest {
    @Test fun detectsFirebaseMessagingPermission() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.chat",
            listOf("com.google.android.c2dm.permission.RECEIVE")
        )
        assertTrue(report.hasFindings)
        assertTrue(report.findings.single().contains("Firebase"))
    }

    @Test fun detectsGoogleMapsMetadata() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.maps",
            emptyList(),
            listOf("com.google.android.geo.API_KEY")
        )
        assertTrue(report.findings.single().contains("Google services"))
    }

    @Test fun detectsFirebaseProviderAuthority() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.chat",
            emptyList(),
            providerAuthorities = listOf("com.example.chat.firebaseinitprovider")
        )
        assertTrue(report.findings.any { it.contains("Firebase") })
    }

    @Test fun detectsFirebaseMessagingService() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.chat",
            emptyList(),
            componentNames = listOf("com.example.chat.FirebaseMessagingService")
        )
        assertTrue(report.findings.any { it.contains("Firebase") })
    }

    @Test fun detectsGoogleSignInComponents() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.auth",
            emptyList(),
            componentNames = listOf("com.example.auth.GoogleSignInActivity")
        )
        assertTrue(report.findings.any { it.contains("authentication") })
    }

    @Test fun detectsSharedUserIdConstraint() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.legacy",
            emptyList(),
            sharedUserId = "com.vendor.shared"
        )
        assertTrue(report.findings.any { it.contains("sharedUserId") })
    }

    @Test fun detectsPlayIntegrityIndicatorsWithoutAttemptingToBypassThem() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.secure",
            emptyList(),
            componentNames = listOf("com.example.secure.IntegrityService")
        )
        assertTrue(report.findings.any { it.contains("does not bypass") })
    }

    @Test fun detectsAppOwnedPermissionsThatMayBreakCrossAppContracts() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.wallet",
            emptyList(),
            declaredPermissions = listOf(
                "com.example.wallet.permission.SYNC",
                "com.vendor.shared.permission.ACCESS"
            )
        )
        assertTrue(report.findings.any { it.contains("cross-app permission contracts") })
        assertTrue(report.findings.single().contains("com.example.wallet.permission.SYNC"))
    }

    @Test fun doesNotFlagUnrelatedDeclaredPermissions() {
        val report = CloneCompatibilityAnalyzer.analyze(
            "com.example.wallet",
            emptyList(),
            declaredPermissions = listOf("com.vendor.shared.permission.ACCESS")
        )
        assertFalse(report.hasFindings)
    }

    @Test fun ordinaryAppGetsGeneralLimitationsButNoFalsePositiveFinding() {
        val report = CloneCompatibilityAnalyzer.analyze("com.example.notes", listOf("android.permission.INTERNET"))
        assertFalse(report.hasFindings)
        assertEquals(3, report.limitations.size)
    }

    @Test fun flagsPlatformPackages() {
        val report = CloneCompatibilityAnalyzer.analyze("com.android.settings", emptyList())
        assertTrue(report.findings.single().contains("platform package"))
    }
}
