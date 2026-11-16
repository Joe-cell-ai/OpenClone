package org.openclone.app.smartremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidTvRemoteFramingTest {
    @Test fun roundTripsSingleBytePayload() {
        val payload = byteArrayOf(0x08, 0x01)
        assertArrayEquals(payload, AndroidTvRemoteFraming.readFrame(
            ByteArrayInputStream(AndroidTvRemoteFraming.encode(payload))
        ))
    }

    @Test fun roundTripsPayloadWhoseLengthUsesMultipleVarintBytes() {
        val payload = ByteArray(300) { (it and 0xff).toByte() }
        assertArrayEquals(payload, AndroidTvRemoteFraming.readFrame(
            ByteArrayInputStream(AndroidTvRemoteFraming.encode(payload))
        ))
    }

    @Test fun cleanEofBeforeFrameReturnsNull() {
        assertNull(AndroidTvRemoteFraming.readFrame(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun truncatedLengthPrefixFailsClosed() {
        assertThrows(EOFException::class.java) {
            AndroidTvRemoteFraming.readFrame(ByteArrayInputStream(byteArrayOf(0x80.toByte())))
        }
    }

    @Test fun truncatedPayloadFailsClosed() {
        assertThrows(EOFException::class.java) {
            AndroidTvRemoteFraming.readFrame(ByteArrayInputStream(byteArrayOf(0x03, 0x01)))
        }
    }

    @Test fun oversizedAndEmptyFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvRemoteFraming.encode(ByteArray(AndroidTvRemoteFraming.MAX_FRAME_SIZE + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvRemoteFraming.encode(byteArrayOf())
        }
    }

    @Test fun writeFrameUsesSameWireEncoding() {
        val payload = byteArrayOf(0x08, 0x01, 0x10, 0x02)
        val output = ByteArrayOutputStream()
        AndroidTvRemoteFraming.writeFrame(output, payload)
        assertArrayEquals(payload, AndroidTvRemoteFraming.readFrame(ByteArrayInputStream(output.toByteArray())))
    }
}
