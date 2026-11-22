package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIconPathEncoderTest {

    @Test
    fun encodesSolidColorRasterAsOnePath() {
        val color = 0xFF123456.toInt()
        val paths = VectorIconPathEncoder.encode(2, 2, IntArray(4) { color })

        assertEquals(1, paths.size)
        assertEquals(color, paths.single().fillColor)
        assertEquals("M0,0h2v1h-2zM0,1h2v1h-2z", paths.single().pathData)
    }

    @Test
    fun skipsTransparentPixelsAndKeepsTheirHoles() {
        val color = 0xFFABCDEF.toInt()
        val paths = VectorIconPathEncoder.encode(
            width = 2,
            height = 1,
            pixels = intArrayOf(0x00000000, color)
        )

        assertEquals(1, paths.size)
        assertEquals("M1,0h1v1h-1z", paths.single().pathData)
    }

    @Test
    fun preservesSemiTransparentPaletteColors() {
        val color = 0x80112233.toInt()
        val paths = VectorIconPathEncoder.encode(1, 1, intArrayOf(color))

        assertEquals(color, paths.single().fillColor)
    }

    @Test
    fun allTransparentRasterProducesNoDrawablePaths() {
        assertTrue(VectorIconPathEncoder.encode(2, 2, IntArray(4)).isEmpty())
    }

    @Test
    fun quantizesLargeColorSetsToBoundedDeterministicPalette() {
        val pixels = IntArray(VectorIconPathEncoder.VIEWPORT_SIZE * VectorIconPathEncoder.VIEWPORT_SIZE) { index ->
            val x = index % VectorIconPathEncoder.VIEWPORT_SIZE
            val y = index / VectorIconPathEncoder.VIEWPORT_SIZE
            (0xFF shl 24) or ((x * 5 and 0xFF) shl 16) or
                ((y * 7 and 0xFF) shl 8) or (index * 11 and 0xFF)
        }

        val first = VectorIconPathEncoder.encode(48, 48, pixels)
        val second = VectorIconPathEncoder.encode(48, 48, pixels)

        assertTrue(first.isNotEmpty())
        assertTrue(first.size <= VectorIconPathEncoder.MAX_COLORS)
        assertTrue(first.sumOf { it.pathData.length } < 80_000)
        assertEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPixelBuffersThatDoNotMatchDimensions() {
        VectorIconPathEncoder.encode(2, 2, intArrayOf(0xFFFFFFFF.toInt()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRastersLargerThanTheConfiguredBound() {
        VectorIconPathEncoder.encode(65, 1, IntArray(65) { 0xFFFFFFFF.toInt() })
    }
}
