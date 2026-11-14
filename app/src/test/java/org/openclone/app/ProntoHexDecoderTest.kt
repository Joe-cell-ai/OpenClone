package org.openclone.app

import org.junit.Assert.*
import org.junit.Test

class ProntoHexDecoderTest {
    @Test fun decodes38KhzRawProntoSignal() {
        val decoded = ProntoHexDecoder.decode("0000 006D 0002 0000 0156 00AB 0156 00AB")
        assertTrue(decoded.carrierHz in 37_000..39_000)
        assertEquals(3, decoded.timingsMicros.size)
        assertTrue(decoded.timingsMicros[0] > decoded.timingsMicros[1])
    }

    @Test fun rejectsUnsupportedProntoType() {
        assertInvalid("0100 006D 0001 0000 0156 00AB")
    }

    @Test fun rejectsMismatchedPulseCount() {
        assertInvalid("0000 006D 0002 0000 0156 00AB")
    }

    @Test fun rejectsMalformedHexWords() {
        assertInvalid("0000 006D 0001 0000 015G 00AB")
    }

    @Test fun rejectsCarrierOutsideSupportedRange() {
        assertInvalid("0000 0001 0001 0000 0001 0001")
    }

    private fun assertInvalid(value: String) {
        try {
            ProntoHexDecoder.decode(value)
            fail("Expected invalid Pronto Hex to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
