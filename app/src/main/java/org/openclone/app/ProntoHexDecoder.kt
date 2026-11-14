package org.openclone.app

/**
 * Converts learned/hosted Pronto Hex (type 0000, modulated raw) into Android
 * ConsumerIrManager timings. Pronto words are big-endian hexadecimal values.
 */
internal object ProntoHexDecoder {
    private const val PRONTO_UNIT_MICROS = 0.241246
    private const val MAX_PAIRS = 255

    fun decode(source: String): InfraredCommand {
        val tokens = source.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        require(tokens.size >= 4) { "Pronto Hex must include its four-word header." }
        require(tokens.size <= 4 + MAX_PAIRS * 2) { "Pronto Hex command is too large." }
        val words = tokens.map { token ->
            require(token.matches(Regex("[0-9A-Fa-f]{4}"))) { "Pronto Hex words must be four hexadecimal digits." }
            token.toInt(16)
        }
        require(words[0] == 0x0000) { "Only modulated raw Pronto Hex (type 0000) is supported." }
        val divisor = words[1]
        require(divisor in 1..0xffff) { "Pronto Hex carrier divisor is invalid." }
        val introPairs = words[2]
        val repeatPairs = words[3]
        val pairCount = introPairs + repeatPairs
        require(introPairs > 0 && pairCount in 1..MAX_PAIRS) { "Pronto Hex must contain 1–255 pulse pairs." }
        require(words.size == 4 + pairCount * 2) { "Pronto Hex pulse count does not match its header." }

        val carrier = (1_000_000.0 / (divisor * PRONTO_UNIT_MICROS)).toInt()
        require(carrier in 20_000..60_000) { "Pronto Hex carrier is outside Android IR's supported safety range." }
        val timings = words.drop(4).map { word ->
            val micros = (word * divisor * PRONTO_UNIT_MICROS).toInt()
            require(micros in 1..100_000) { "Pronto Hex contains an out-of-range pulse duration." }
            micros
        }.toMutableList()

        // ConsumerIrManager expects an alternating mark/space pattern. The
        // trailing space is not needed to transmit the signal and is omitted.
        if (timings.size % 2 == 0) timings.removeAt(timings.lastIndex)
        require(timings.size in 3..511) { "Decoded IR pattern is outside Android's supported size." }
        require(timings.sumOf { it.toLong() } <= 2_000_000L) { "Decoded IR command exceeds the 2-second safety limit." }
        return InfraredCommand(carrier, timings.toIntArray())
    }
}
