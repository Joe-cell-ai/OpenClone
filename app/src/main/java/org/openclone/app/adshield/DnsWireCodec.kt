package org.openclone.app.adshield

/**
 * Minimal bounded DNS wire codec for standard single-question UDP DNS queries.
 *
 * Unsupported or malformed packets return null; callers must forward them
 * unchanged rather than fabricate a filtering decision.
 */
object DnsWireCodec {
    data class Question(
        val transactionId: Int,
        val hostname: String,
        val queryType: Int,
        val queryClass: Int,
        internal val questionEndOffset: Int,
        internal val requestFlags: Int,
        internal val additionalCount: Int
    )

    fun parseSingleQuestion(packet: ByteArray): Question? {
        if (packet.size < 17 || packet.size > 4096) return null
        val flags = u16(packet, 2) ?: return null
        val questionCount = u16(packet, 4) ?: return null
        val answerCount = u16(packet, 6) ?: return null
        val authorityCount = u16(packet, 8) ?: return null
        val additionalCount = u16(packet, 10) ?: return null
        if ((flags and 0x8000) != 0 || (flags and 0x7800) != 0) return null
        if (questionCount != 1 || answerCount != 0 || authorityCount != 0 || additionalCount !in 0..1) return null

        var offset = 12
        val labels = ArrayList<String>()
        var totalNameLength = 0
        while (true) {
            if (offset >= packet.size) return null
            val length = packet[offset++].toInt() and 0xff
            if (length == 0) break
            if ((length and 0xc0) != 0 || length > 63 || offset + length > packet.size) return null
            val labelBytes = packet.copyOfRange(offset, offset + length)
            if (labelBytes.any { byte ->
                    val value = byte.toInt() and 0xff
                    value !in 0x21..0x7e
                }) return null
            val label = labelBytes.toString(Charsets.US_ASCII)
            labels += label
            totalNameLength += length + 1
            if (totalNameLength > 254 || labels.size > 127) return null
            offset += length
        }
        if (labels.isEmpty() || offset + 4 > packet.size) return null
        val hostname = DomainBlocklist.normalize(labels.joinToString(".")) ?: return null
        val queryType = u16(packet, offset) ?: return null
        val queryClass = u16(packet, offset + 2) ?: return null
        offset += 4
        if (additionalCount == 1) {
            // Accept the common EDNS0 OPT pseudo-record only; reject arbitrary trailing RRs.
            if (offset + 11 > packet.size || packet[offset] != 0.toByte()) return null
            if (u16(packet, offset + 1) != 41) return null
            val dataLength = u16(packet, offset + 9) ?: return null
            offset += 11
            if (dataLength > 1232 || offset + dataLength != packet.size) return null
        } else if (offset != packet.size) return null
        return Question(
            transactionId = u16(packet, 0) ?: return null,
            hostname = hostname,
            queryType = queryType,
            queryClass = queryClass,
            questionEndOffset = packet.size,
            requestFlags = flags,
            additionalCount = additionalCount
        )
    }

    /** Builds a standards-shaped NXDOMAIN response preserving the original question. */
    fun buildNxDomainResponse(request: ByteArray, question: Question): ByteArray {
        require(question.questionEndOffset == request.size && request.size >= 17)
        val response = ByteArray(12 + (request.size - 12))
        request.copyInto(response, 12, 12, request.size)
        putU16(response, 0, question.transactionId)
        val responseFlags = 0x8000 or (question.requestFlags and 0x0100) or 0x0003
        putU16(response, 2, responseFlags)
        putU16(response, 4, 1)
        putU16(response, 6, 0)
        putU16(response, 8, 0)
        putU16(response, 10, question.additionalCount)
        return response
    }

    /** Builds a DNS SERVFAIL response when the encrypted upstream cannot be reached. */
    fun buildServFailResponse(request: ByteArray, question: Question): ByteArray {
        require(question.questionEndOffset == request.size && request.size >= 17)
        val response = ByteArray(request.size)
        request.copyInto(response)
        val responseFlags = 0x8000 or (question.requestFlags and 0x0100) or 0x0080 or 0x0002
        putU16(response, 2, responseFlags)
        putU16(response, 6, 0)
        putU16(response, 8, 0)
        return response
    }

    private fun u16(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 1 >= bytes.size) return null
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
