package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconReplacementDiagnosticsTest {

    @Test
    fun classifiesPngAndXmlResourcePathsCaseInsensitively() {
        assertEquals(IconResourceKind.PNG, IconResourceKind.fromPath("res/mipmap-hdpi/ic_launcher.PNG"))
        assertEquals(IconResourceKind.XML, IconResourceKind.fromPath("res/mipmap-anydpi-v26/ic_launcher.xml"))
    }

    @Test
    fun otherRasterFormatsRemainExplicitlyOutsideTheCurrentReplacementPath() {
        assertEquals(IconResourceKind.OTHER, IconResourceKind.fromPath("res/mipmap-xxhdpi/ic_launcher.webp"))
        assertEquals(IconResourceKind.OTHER, IconResourceKind.fromPath("res/drawable/ic_launcher.jpg"))
        assertEquals(IconResourceKind.OTHER, IconResourceKind.fromPath("res/drawable/ic_launcher.avif"))
    }

    @Test
    fun formatsActionableResourceDiagnosticWithPathIdAndDensity() {
        val line = IconReplacementDiagnostic(
            action = IconReplacementAction.CUSTOM_ICON,
            resourceId = 0x7f080123,
            resourcePath = "res/mipmap-hdpi/ic_launcher.xml",
            densityDpi = 240,
            status = IconDiagnosticStatus.UNSUPPORTED,
            detail = "VectorDrawable foreground replacement is not implemented yet."
        ).toLogLine()

        assertTrue(line.contains("ICON custom-icon unsupported"))
        assertTrue(line.contains("res/mipmap-hdpi/ic_launcher.xml"))
        assertTrue(line.contains("id=0x7f080123"))
        assertTrue(line.contains("density=240dpi"))
        assertTrue(line.contains("VectorDrawable foreground"))
    }

    @Test
    fun unresolvedResourceStillReportsItsResourceIdAndDefaultDensity() {
        val line = IconReplacementDiagnostic(
            action = IconReplacementAction.BADGE,
            resourceId = 0x7f080456,
            resourcePath = null,
            densityDpi = 0,
            status = IconDiagnosticStatus.FAILED,
            detail = "Resource lookup failed."
        ).toLogLine()

        assertTrue(line.contains("resource=<unresolved>"))
        assertTrue(line.contains("id=0x7f080456"))
        assertTrue(line.contains("density=default"))
    }
}
