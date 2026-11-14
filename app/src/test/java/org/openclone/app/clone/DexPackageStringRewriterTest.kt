package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Test

class DexPackageStringRewriterTest {
    @Test
    fun rewritesExactPackageIdentityWithoutRenamingClassNames() {
        assertEquals(
            "com.example.clone",
            DexPackageStringRewriter.replacePackageIdentityToken(
                "com.example.app", "com.example.app", "com.example.clone"
            )
        )
        assertEquals(
            "com.example.app.registration.RegisterPhone",
            DexPackageStringRewriter.replacePackageIdentityToken(
                "com.example.app.registration.RegisterPhone", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun rewritesFlattenedRelativeComponentAndExpandsItsClassName() {
        assertEquals(
            "com.example.clone/com.example.app.registration.RegisterPhone",
            DexPackageStringRewriter.replacePackageIdentityToken(
                "com.example.app/.registration.RegisterPhone", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun preservesUnrelatedComponentPackages() {
        assertEquals(
            "com.other.app/.MainActivity",
            DexPackageStringRewriter.replacePackageIdentityToken(
                "com.other.app/.MainActivity", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun replacesExactDottedPackageTokens() {
        assertEquals(
            "content://com.example.clone/provider",
            DexPackageStringRewriter.replacePackageToken(
                "content://com.example.app/provider", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun replacesPackagePrefixesInComponentAuthorities() {
        assertEquals(
            "com.example.clone.files;com.example.clone.images",
            DexPackageStringRewriter.replacePackageToken(
                "com.example.app.files;com.example.app.images",
                "com.example.app",
                "com.example.clone"
            )
        )
    }

    @Test
    fun replacesSlashSeparatedPackageTokens() {
        assertEquals(
            "assets/com/example/clone/config",
            DexPackageStringRewriter.replacePackageToken(
                "assets/com/example/app/config", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun doesNotReplaceLongerUnrelatedIdentifiers() {
        assertEquals(
            "com.example.application and xcom.example.app",
            DexPackageStringRewriter.replacePackageToken(
                "com.example.application and xcom.example.app",
                "com.example.app",
                "com.example.clone"
            )
        )
    }

    @Test
    fun doesNotReplacePackageWhenFollowedByIdentifierCharacter() {
        assertEquals(
            "com.example.app_extra;com.example.clone",
            DexPackageStringRewriter.replacePackageToken(
                "com.example.app_extra;com.example.app", "com.example.app", "com.example.clone"
            )
        )
    }

    @Test
    fun leavesUnrelatedStringsUnchanged() {
        val value = "https://example.com/help"
        assertEquals(value, DexPackageStringRewriter.replacePackageToken(value, "com.example.app", "com.example.clone"))
    }
}
