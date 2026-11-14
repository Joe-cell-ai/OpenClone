package org.openclone.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tun2proxyNativeLibraryTest {
    @Test
    fun pinnedAndroidNativeLibraryLoadsAndExportsJniBridge() {
        val bridge = Class.forName("com.github.shadowsocks.bg.Tun2proxy")
        assertNotNull(bridge.getDeclaredMethod("run", String::class.java, Char::class.javaPrimitiveType))
        assertNotNull(bridge.getDeclaredMethod("stop"))
    }
}
