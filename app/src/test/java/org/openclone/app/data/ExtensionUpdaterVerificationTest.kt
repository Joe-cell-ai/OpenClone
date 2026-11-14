package org.openclone.app.data

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExtensionUpdaterVerificationTest {
    @Test
    fun signedCatalogueAcceptsOnlyValidSignatureAndHttpsEntries() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = JSONObject()
            .put("formatVersion", 1)
            .put("extensions", JSONArray().put(
                JSONObject()
                    .put("id", "org.openclone.sample")
                    .put("version", "1.2.3")
                    .put("publisher", "Example Publisher")
                    .put("minimumOpenCloneApi", 1)
                    .put("maximumOpenCloneApi", 2)
                    .put("sha256", "a".repeat(64))
                    .put("downloadUrl", "https://downloads.example.org/sample.ocx")
                    .put("signature", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            )).toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload)
        val envelope = SignedExtensionCatalogue(
            publisherId = "publisher-1",
            payloadBase64 = Base64.getEncoder().encodeToString(payload),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())
        )

        val verified = ExtensionCatalogueVerifier.verify(envelope, pair.public, currentApi = 1)
        assertNotNull(verified)
        assertEquals(1, verified!!.size)
        assertEquals("org.openclone.sample", verified.single().id)

        val tampered = envelope.copy(payloadBase64 = Base64.getEncoder().encodeToString(
            payload.toString(Charsets.UTF_8).replace("1.2.3", "1.2.4").toByteArray(Charsets.UTF_8)
        ))
        assertNull(ExtensionCatalogueVerifier.verify(tampered, pair.public, currentApi = 1))
    }

    @Test
    fun catalogueFiltersIncompatibleExtensions() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = JSONObject().put("formatVersion", 1).put("extensions", JSONArray().put(
            JSONObject().put("id", "org.openclone.future").put("version", "2.0.0")
                .put("publisher", "Future").put("minimumOpenCloneApi", 9).put("maximumOpenCloneApi", 10)
                .put("sha256", "b".repeat(64)).put("downloadUrl", "https://example.org/future.ocx")
                .put("signature", "AA==")
        )).toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload)
        val envelope = SignedExtensionCatalogue("future", Base64.getEncoder().encodeToString(payload),
            Base64.getEncoder().encodeToString(signer.sign()))

        assertEquals(emptyList<CatalogueExtension>(), ExtensionCatalogueVerifier.verify(envelope, pair.public, currentApi = 1))
    }

    @Test
    fun catalogueRejectsNonHttpsDownloadUrls() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = JSONObject().put("formatVersion", 1).put("extensions", JSONArray().put(
            JSONObject().put("id", "org.openclone.sample").put("version", "1.0.0")
                .put("publisher", "Publisher").put("minimumOpenCloneApi", 1).put("maximumOpenCloneApi", 2)
                .put("sha256", "c".repeat(64)).put("downloadUrl", "http://example.org/sample.ocx")
                .put("signature", "AA==")
        )).toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload)
        val envelope = SignedExtensionCatalogue("publisher", Base64.getEncoder().encodeToString(payload),
            Base64.getEncoder().encodeToString(signer.sign()))

        assertNull(ExtensionCatalogueVerifier.verify(envelope, pair.public, currentApi = 1))
    }
}
