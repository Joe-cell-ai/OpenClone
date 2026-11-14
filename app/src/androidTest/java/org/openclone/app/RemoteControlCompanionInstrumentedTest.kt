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
            RemoteControlServerState.stopped()
            context.startForegroundService(service)
            // Wait for the service's explicit bind signal instead of making repeated
            // long socket attempts while Android is still starting the TLS listener.
            val deadline = System.currentTimeMillis() + 20_000
            while (!RemoteControlServerState.isListening &&
                RemoteControlServerState.failure == null &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(100)
            }
            val startupFailure = RemoteControlServerState.failure
            assertTrue(
                "Remote service did not bind. Startup failure: $startupFailure; diagnostics: " +
                    ExtensionDiagnosticsLog(context).getAll().filter { it.area == "remote" }.takeLast(5),
                RemoteControlServerState.isListening
            )
            val endpoint = RemoteControlPairingEndpoint.parse(pairing.toUri()).copy(host = "127.0.0.1")
            val response = RemoteControlCompanionClient.status(endpoint)
            assertTrue("Companion status response was invalid: $response", response.contains("\\"ok\\":true"))
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
