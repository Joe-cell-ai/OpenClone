package org.openclone.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InfraredCommandTest {
    @Test fun parsesValidRawCommand() {
        val command = InfraredCommand.parse("38000", "9000, 4500, 560, 560, 560", null)
        assertEquals(38000, command.carrierHz)
        assertArrayEquals(intArrayOf(9000, 4500, 560, 560, 560), command.timingsMicros)
    }

    @Test fun rejectsEvenNumberOfDurations() {
        assertThrows(IllegalArgumentException::class.java) {
            InfraredCommand.parse("38000", "9000,4500,560,560", null)
        }
    }

    @Test fun rejectsOutOfRangeDurations() {
        assertThrows(IllegalArgumentException::class.java) {
            InfraredCommand.parse("38000", "9000,4500,0", null)
        }
    }

    @Test fun rejectsUnsupportedCarrierRange() {
        assertThrows(IllegalArgumentException::class.java) {
            InfraredCommand.parse("10000", "9000,4500,560", null)
        }
    }
}
