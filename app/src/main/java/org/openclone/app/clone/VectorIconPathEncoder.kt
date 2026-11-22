package org.openclone.app.clone

/** One solid-colour path in a bounded, raster-derived VectorDrawable. */
internal data class EncodedVectorPath(val fillColor: Int, val pathData: String)

/**
 * Turns a small raster into VectorDrawable path data without adding a native or third-party
 * tracing dependency. The input is deliberately bounded to keep generated APK resources modest.
 */
internal object VectorIconPathEncoder {
    const val VIEWPORT_SIZE = 48
    const val MAX_COLORS = 32
    private const val MAX_DIMENSION = 64
    private const val MIN_ALPHA = 8

    private data class ColorCount(val color: Int, val count: Int)

    fun encode(
        width: Int,
        height: Int,
        pixels: IntArray,
        maxColors: Int = MAX_COLORS
    ): List<EncodedVectorPath> {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Vector icon raster dimensions must be between 1 and " + MAX_DIMENSION + " pixels."
        }
        require(pixels.size == width * height) { "Pixel buffer does not match its dimensions." }
        require(maxColors in 1..MAX_COLORS) { "Vector icon palette must contain 1–" + MAX_COLORS + " colors." }

        val counts = LinkedHashMap<Int, Int>()
        pixels.forEach { color ->
            if ((color ushr 24) > MIN_ALPHA) counts[color] = (counts[color] ?: 0) + 1
        }
        if (counts.isEmpty()) return emptyList()

        val samples = counts.map { (color, count) -> ColorCount(color, count) }
        val palette = medianCut(samples, maxColors)
        val paletteBySourceColor = HashMap<Int, Int>(samples.size)
        samples.forEach { sample ->
            var nearestIndex = 0
            var nearestDistance = Long.MAX_VALUE
            palette.forEachIndexed { index, color ->
                val distance = colorDistance(sample.color, color)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestIndex = index
                }
            }
            paletteBySourceColor[sample.color] = nearestIndex
        }

        val paths = Array(palette.size) { StringBuilder() }
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val sourceColor = pixels[y * width + x]
                val paletteIndex = if ((sourceColor ushr 24) <= MIN_ALPHA) -1
                    else paletteBySourceColor[sourceColor] ?: -1
                if (paletteIndex < 0) {
                    x++
                    continue
                }

                val start = x
                x++
                while (x < width) {
                    val nextColor = pixels[y * width + x]
                    val nextIndex = if ((nextColor ushr 24) <= MIN_ALPHA) -1
                        else paletteBySourceColor[nextColor] ?: -1
                    if (nextIndex != paletteIndex) break
                    x++
                }
                val runWidth = x - start
                paths[paletteIndex]
                    .append('M').append(start).append(',').append(y)
                    .append('h').append(runWidth)
                    .append("v1h-").append(runWidth).append('z')
            }
        }

        return palette.indices.mapNotNull { index ->
            paths[index].takeIf { it.isNotEmpty() }?.let {
                EncodedVectorPath(palette[index], it.toString())
            }
        }
    }

    private fun medianCut(samples: List<ColorCount>, maxColors: Int): List<Int> {
        if (samples.size <= maxColors) return samples.map { it.color }

        val buckets = mutableListOf(samples)
        while (buckets.size < maxColors) {
            val candidateIndex = buckets.indices
                .filter { buckets[it].size > 1 }
                .maxByOrNull { index ->
                    val bucket = buckets[index]
                    val channel = widestChannel(bucket)
                    channelRange(bucket, channel)
                } ?: break

            val bucket = buckets.removeAt(candidateIndex)
            val channel = widestChannel(bucket)
            val sorted = bucket.sortedBy { channelValue(it.color, channel) }
            val totalWeight = sorted.sumOf { it.count }
            var accumulated = 0
            var splitAt = 1
            for (index in 0 until sorted.lastIndex) {
                accumulated += sorted[index].count
                if (accumulated * 2 >= totalWeight) {
                    splitAt = index + 1
                    break
                }
            }
            splitAt = splitAt.coerceIn(1, sorted.lastIndex)
            buckets += sorted.subList(0, splitAt)
            buckets += sorted.subList(splitAt, sorted.size)
        }
        return buckets.map(::averageColor)
    }

    private fun widestChannel(bucket: List<ColorCount>): Int =
        (0..3).maxBy { channelRange(bucket, it) }

    private fun channelRange(bucket: List<ColorCount>, channel: Int): Int {
        var minimum = 255
        var maximum = 0
        bucket.forEach {
            val value = channelValue(it.color, channel)
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        return maximum - minimum
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> color ushr 24 and 0xFF
        1 -> color ushr 16 and 0xFF
        2 -> color ushr 8 and 0xFF
        else -> color and 0xFF
    }

    private fun averageColor(bucket: List<ColorCount>): Int {
        val total = bucket.sumOf { it.count }.coerceAtLeast(1)
        fun average(channel: Int): Int =
            (bucket.sumOf { channelValue(it.color, channel).toLong() * it.count } / total).toInt()
        return (average(0) shl 24) or (average(1) shl 16) or
            (average(2) shl 8) or average(3)
    }

    /** Compare premultiplied channels so transparent edge pixels do not dominate palette splits. */
    private fun colorDistance(first: Int, second: Int): Long {
        val firstAlpha = first ushr 24 and 0xFF
        val secondAlpha = second ushr 24 and 0xFF
        fun premultiplied(color: Int, alpha: Int, shift: Int): Int =
            ((color ushr shift and 0xFF) * alpha) / 255
        val alphaDelta = firstAlpha - secondAlpha
        val redDelta = premultiplied(first, firstAlpha, 16) - premultiplied(second, secondAlpha, 16)
        val greenDelta = premultiplied(first, firstAlpha, 8) - premultiplied(second, secondAlpha, 8)
        val blueDelta = premultiplied(first, firstAlpha, 0) - premultiplied(second, secondAlpha, 0)
        return alphaDelta.toLong() * alphaDelta + redDelta.toLong() * redDelta +
            greenDelta.toLong() * greenDelta + blueDelta.toLong() * blueDelta
    }
}
