package org.openclone.app.data

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSuiteCoreTest {
    @Test
    fun privacyAuditOnlyAllowsRemovedPermissionsThatWereRequested() {
        val valid = PrivacyAuditRepository.validate(
            "com.example.source",
            "com.example.clone",
            listOf("android.permission.CAMERA", "android.permission.INTERNET"),
            listOf("android.permission.CAMERA")
        )
        assertTrue(valid.second.contains("android.permission.CAMERA"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun privacyAuditRejectsUnrequestedRemoval() {
        PrivacyAuditRepository.validate(
            "com.example.source",
            "com.example.clone",
            listOf("android.permission.INTERNET"),
            listOf("android.permission.CAMERA")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun privacyAuditRejectsInvalidPackageName() {
        PrivacyAuditRepository.validate(
            "not a package",
            "com.example.clone",
            emptyList(),
            emptyList()
        )
    }

    @Test
    fun signedExtensionDescriptorVerifiesPackageAndPublisherSignature() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val bytes = "safe extension package fixture".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val unsigned = SignedExtensionDescriptor(
            extensionId = "org.openclone.sample",
            version = "1.2.3",
            publisher = "OpenClone Test Publisher",
            minimumOpenCloneApi = 1,
            maximumOpenCloneApi = 3,
            sha256 = digest,
            signatureBase64 = ""
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(unsigned.canonicalPayload())
        val signed = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))

        assertTrue(ExtensionPackageVerifier.verify(signed, bytes, pair.public))
        assertFalse(ExtensionPackageVerifier.verify(signed, "tampered".toByteArray(), pair.public))
        assertFalse(ExtensionPackageVerifier.verify(signed.copy(version = "1.2.4"), bytes, pair.public))
    }

    @Test
    fun remoteControlRequestRequiresValidMacAndFreshTimestamp() {
        val secret = RemoteControlAuthenticator.createPairingSecret()
        val now = 1_800_000_000_000L
        val nonce = "nonce_0123456789abcdef"
        val signature = RemoteControlAuthenticator.sign(secret, "POST", "/v1/status", now, nonce)

        assertTrue(RemoteControlAuthenticator.verify(secret, "POST", "/v1/status", now, nonce, signature, now))
        assertFalse(RemoteControlAuthenticator.verify(secret, "DELETE", "/v1/status", now, nonce, signature, now))
        assertFalse(RemoteControlAuthenticator.verify(secret, "POST", "/v1/status", now, nonce, signature, now + 120_000))
    }

    @Test
    fun webViewPolicyAllowsOnlyHttpsAllowlistedHosts() {
        val policy = ManagedWebViewPolicy(allowedHosts = setOf("docs.example.com"))
        assertTrue(policy.allows("https://docs.example.com/help"))
        assertFalse(policy.allows("http://docs.example.com/help"))
        assertFalse(policy.allows("https://evil.example.com/"))
    }
}
