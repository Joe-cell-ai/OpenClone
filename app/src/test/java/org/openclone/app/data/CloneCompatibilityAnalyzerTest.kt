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
