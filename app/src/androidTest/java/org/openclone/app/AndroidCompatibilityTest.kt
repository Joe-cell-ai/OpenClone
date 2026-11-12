package org.openclone.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCompatibilityTest {
    @Test
    fun mainActivityStartsOnTheDeviceApiLevel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("org.openclone.app", activity.packageName)
                assertNotNull(activity.window)
            }
        }
    }

    @Test
    fun androidKeystoreProviderIsAvailable() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        assertFalse(keyStore.containsAlias("openclone-compatibility-probe"))
    }

    @Test
    fun applicationContextUsesTheExpectedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.openclone.app", context.packageName)
    }
}
