package org.openclone.app.clone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNameValidationTest {
    @Test
    fun acceptsAndroidStylePackageNames() {
        listOf(
            "com.example.app",
            "org.openclone.app2",
            "io.github.open_clone",
            "a.b"
        ).forEach { assertTrue(it, ApkCloneEngine.PACKAGE_NAME.matches(it)) }
    }

    @Test
    fun rejectsMalformedPackageNames() {
        listOf(
            "example",
            ".com.example",
            "com..example",
            "com.2example",
            "com.example.",
            "com.example-name",
            "com.example app"
        ).forEach { assertFalse(it, ApkCloneEngine.PACKAGE_NAME.matches(it)) }
    }
}
