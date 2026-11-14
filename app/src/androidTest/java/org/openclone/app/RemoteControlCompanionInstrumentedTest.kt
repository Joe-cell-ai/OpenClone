package org.openclone.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteControlCompanionInstrumentedTest {
    @Test
    fun companionAuthenticatesAgainstPinnedLocalServiceAndReceivesStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pairing = RemoteControlPairingStore.create(context)
        val service = Intent(context, RemoteControlService::class.java)
        try {
            context.startForegroundService(service)
            // The foreground service binds asynchronously; retry only transient startup failures.
            var response: String? = null
            var lastFailure: Throwable? = null
            repeat(12) {
                if (response == null) {
                    try {
                        val endpoint = pairing.copy(host = "127.0.0.1")
                        response = RemoteControlCompanionClient.status(endpoint)
                    } catch (error: Throwable) {
                        lastFailure = error
                        Thread.sleep(250)
                    }
                }
            }
            assertTrue("Companion could not authenticate with the local service: ${lastFailure?.message}", response?.contains("\"ok\":true") == true)
        } finally {
            context.stopService(service)
            RemoteControlPairingStore.revoke(context)
        }
    }

    @Test
    fun pairingParserRejectsWrongSchemeAndMalformedFingerprint() {
        val wrongScheme = runCatching {
            RemoteControlPairingEndpoint.parse("https://pair?v=1&host=127.0.0.1&port=38473&fingerprint=${"a".repeat(64)}&secret=${"A".repeat(43)}")
        }
        assertTrue(wrongScheme.isFailure)
        val badFingerprint = runCatching {
            RemoteControlPairingEndpoint.parse("openclone://pair?v=1&host=127.0.0.1&port=38473&fingerprint=bad&secret=${"A".repeat(43)}")
        }
        assertTrue(badFingerprint.isFailure)
    }
}
