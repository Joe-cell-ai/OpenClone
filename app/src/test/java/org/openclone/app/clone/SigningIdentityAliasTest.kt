package org.openclone.app.clone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningIdentityAliasTest {
    @Test
    fun acceptsSupportedIdentityNames() {
        listOf("OpenClone", "Work key 2", "personal.key", "backup_key", "legacy-key").forEach {
            assertTrue(it, runCatching { SigningIdentityAlias.validate(it) }.isSuccess)
        }
    }

    @Test
    fun rejectsBlankMalformedAndOverlongNames() {
        listOf("", " starts-with-space", ".hidden", "name/with/slash", "key@home", "x".repeat(65)).forEach {
            assertFalse(it, runCatching { SigningIdentityAlias.validate(it) }.isSuccess)
        }
    }
}
