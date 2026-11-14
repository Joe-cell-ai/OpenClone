package org.openclone.app.data

import java.security.KeyFactory
import java.security.MessageDigest
import java.util.Base64
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HostedCatalogueTest {
    @Test
    fun repositorySeedCatalogueVerifiesAgainstPublishedPublisherKey() {
        val publicKeyBytes = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFy/nyuFgixka7Vj+cH7GhqYIla3r" +
                "aZUAeujcQU72BYVXhgjilnwKGAaT/rSau4SrHvvhO9/ql9tMQrnRRKyKHQ=="
        )
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals("f33f2fb1fb231984a2234160", fingerprint.take(24))

        val envelope = SignedExtensionCatalogue(
            publisherId = "f33f2fb1fb231984a2234160",
            payloadBase64 = "eyJmb3JtYXRWZXJzaW9uIjoxLCJleHRlbnNpb25zIjpbXX0=",
            signatureBase64 = "MEQCIF/jRKbhj7HQvrPaL1ZWl+tc6Gnti35h93A/Z7LN/x8MAiA1TiM3BC2FmDiuQzTtF06rh61QRxCpWnNYNRZIoEL2ow=="
        )
        val verified = ExtensionCatalogueVerifier.verify(envelope, key, currentApi = 1)
        assertNotNull(verified)
        assertEquals(emptyList<CatalogueExtension>(), verified)
    }
}
