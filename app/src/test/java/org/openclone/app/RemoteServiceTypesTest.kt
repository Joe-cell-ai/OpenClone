package org.openclone.app

import org.junit.Assert.*
import org.junit.Test

class RemoteServiceTypesTest {
    @Test fun recognizesOnlyExplicitlySupportedServiceTypes() {
        assertEquals(RemoteTransport.WIFI_LAN, RemoteServiceTypes.transportFor(RemoteServiceTypes.GOOGLE_CAST))
        assertEquals(RemoteTransport.WIFI_LAN, RemoteServiceTypes.transportFor(RemoteServiceTypes.AIRPLAY))
        assertEquals(RemoteTransport.WIFI_LAN, RemoteServiceTypes.transportFor(RemoteServiceTypes.MATTER_OPERATIONAL))
        assertEquals(RemoteTransport.WIFI_LAN, RemoteServiceTypes.transportFor(RemoteServiceTypes.MATTER_COMMISSIONABLE))
        assertNull(RemoteServiceTypes.transportFor("_unknown._tcp."))
    }

    @Test fun serviceLabelsDoNotClaimControlSupport() {
        assertTrue(RemoteServiceTypes.protocolLabel(RemoteServiceTypes.GOOGLE_CAST).contains("advertisement"))
        assertTrue(RemoteServiceTypes.protocolLabel(RemoteServiceTypes.MATTER_OPERATIONAL).contains("advertisement"))
    }

    @Test fun scannerServiceTypesAreBoundedAndKnown() {
        assertEquals(4, RemoteServiceTypes.supported.size)
        assertTrue(RemoteServiceTypes.supported.all { it.startsWith("_") && it.endsWith(".") })
    }
}
