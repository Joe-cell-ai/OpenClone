package org.openclone.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDiscoveryPermissionsTest {
    @Test fun android12AndLaterRequestScanAndConnectOnly() {
        val permissions = BleDiscoveryPermissions.requiredForSdk(31).toSet()
        assertEqualsSet(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            permissions
        )
        assertFalse(permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test fun scanDurationAndResultCountAreBounded() {
        assertTrue(BleDiscoveryPermissions.SCAN_DURATION_MS in 1_000L..30_000L)
        assertTrue(BleDiscoveryPermissions.MAX_RESULTS in 1..128)
    }

    @Test fun android11AndEarlierUseFineLocationForBleScanning() {
        val permissions = BleDiscoveryPermissions.requiredForSdk(30)
        assertArrayEquals(arrayOf("android.permission.ACCESS_FINE_LOCATION"), permissions)
    }

    private fun assertEqualsSet(expected: Set<String>, actual: Set<String>) {
        assertTrue("Expected $expected but got $actual", expected == actual)
    }
}
