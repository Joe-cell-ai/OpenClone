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

    @Test
    fun missingWorkerOverrideUsesAppSpecificDefault() {
        assertTrue(DexRewritePolicy.resolveEnabled("com.whatsapp", null))
        assertFalse(DexRewritePolicy.resolveEnabled("com.example.app", null))
    }

    @Test
    fun whatsappRewriteCannotBeDisabledByWorkerOverride() {
        assertTrue(DexRewritePolicy.resolveEnabled("com.whatsapp", false))
        assertTrue(DexRewritePolicy.resolveEnabled("com.whatsapp", true))
        assertTrue(DexRewritePolicy.resolveEnabled("com.whatsapp", null))
    }

    @Test
    fun nonWhatsAppAppsStillRespectExplicitWorkerOverride() {
        assertFalse(DexRewritePolicy.resolveEnabled("com.example.app", false))
        assertTrue(DexRewritePolicy.resolveEnabled("com.example.app", true))
        assertFalse(DexRewritePolicy.resolveEnabled("com.example.app", null))
    }
}
