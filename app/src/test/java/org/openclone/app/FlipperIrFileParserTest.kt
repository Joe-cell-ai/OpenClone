package org.openclone.app

import org.junit.Assert.*
import org.junit.Test

class FlipperIrFileParserTest {
    @Test fun parsesRawCommandsAndDropsTrailingSpace() {
        val commands = FlipperIrFileParser.parse("""
            Filetype: IR signals file
            Version: 1
            #
            name: POWER
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: 9000 4500 560 560
        """.trimIndent())
        assertEquals(1, commands.size)
        assertEquals("POWER", commands.single().name)
        assertEquals(3, commands.single().command.timingsMicros.size)
    }

    @Test fun rejectsParsedProtocolUntilEncoderExists() {
        try {
            FlipperIrFileParser.parse("""
                Filetype: IR signals file
                Version: 1
                name: POWER
                type: parsed
                protocol: NEC
                address: 00 00 00 00
                command: 01 00 00 00
            """.trimIndent())
            fail("Expected unsupported parsed protocol to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsOversizedFile() {
        try {
            FlipperIrFileParser.parse("x".repeat(65 * 1024))
            fail("Expected oversized file to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
