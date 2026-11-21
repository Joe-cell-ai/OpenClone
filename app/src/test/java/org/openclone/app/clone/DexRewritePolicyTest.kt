package org.openclone.app.clone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexRewritePolicyTest {
    @Test
    fun enablesRewriteByDefaultForWhatsAppOnly() {
        assertTrue(DexRewritePolicy.defaultEnabledFor("com.whatsapp"))
        assertFalse(DexRewritePolicy.defaultEnabledFor("com.whatsapp.business"))
        assertFalse(DexRewritePolicy.defaultEnabledFor("com.example.app"))
        assertFalse(DexRewritePolicy.defaultEnabledFor(""))
    }
}
