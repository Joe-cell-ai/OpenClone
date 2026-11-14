package org.openclone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxyProfileTest {
    @Test
    fun acceptsValidProxyEndpoint() {
        ProxyProfile.validate("Office", "proxy.example.com", 8080)
        assertEquals(8080, ProxyProfile("Office", "proxy.example.com", 8080).port)
    }

    @Test
    fun rejectsBlankName() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfile.validate(" ", "proxy.example.com", 8080)
        }
    }

    @Test
    fun rejectsInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfile.validate("Office", "proxy.example.com", 65536)
        }
    }

    @Test
    fun rejectsPathOrCredentialsInHost() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfile.validate("Office", "user@proxy.example.com/path", 8080)
        }
    }
}
