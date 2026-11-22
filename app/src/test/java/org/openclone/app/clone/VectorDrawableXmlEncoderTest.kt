package org.openclone.app.clone

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorDrawableXmlEncoderTest {

    @Test
    fun emitsCompiledBinaryXmlWithBoundedSize() {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
            0x00000000, 0xFF0000FF.toInt()
        )
        val bytes = VectorDrawableXmlEncoder.encode(2, 2, pixels)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertTrue(bytes.size > 8)
        assertEquals(0x0003, header.getShort(0).toInt() and 0xFFFF)
        assertEquals(bytes.size, header.getInt(4))
        assertTrue(bytes.size < 32 * 1024)
    }
}
