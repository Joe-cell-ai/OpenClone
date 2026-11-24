package org.openclone.app.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLaunchGateTest {
    @Test fun existingStoredInstanceReportsRuntimeUnavailableRatherThanSuccess() {
        val result = GuestLaunchGate().requestLaunch("instance-1", instanceExists = true)

        assertTrue(result is GuestLaunchResult.RuntimeUnavailable)
        assertEquals("instance-1", (result as GuestLaunchResult.RuntimeUnavailable).instanceId)
    }

    @Test fun missingInstanceIsReportedSeparately() {
        val result = GuestLaunchGate().requestLaunch("missing", instanceExists = false)

        assertEquals(GuestLaunchResult.InstanceMissing("missing"), result)
    }
}
