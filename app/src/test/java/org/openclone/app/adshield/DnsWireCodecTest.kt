package org.openclone.app.adshield

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireCodecTest {
    private fun query(host: String, flags: Int = 0x0100): ByteArray {
        val labels = host.split(".")
        val name = ArrayList<Byte>()
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            name.add(bytes.size.toByte())
            bytes.forEach(name::add)
        }
        name.add(0)
        val packet = ByteArray(12 + name.size + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[2] = (flags ushr 8).toByte()
        packet[3] = flags.toByte()
        packet[5] = 1
        name.forEachIndexed { index, byte -> packet[12 + index] = byte }
        val typeOffset = 12 + name.size
        packet[typeOffset + 1] = 1 // A
        packet[typeOffset + 3] = 1 // IN
        return packet
    }

    @Test fun parsesStandardAQuestion() {
        val packet = query("ads.example")
        val parsed = DnsWireCodec.parseSingleQuestion(packet)!!
        assertEquals(0x1234, parsed.transactionId)
        assertEquals("ads.example", parsed.hostname)
        assertEquals(1, parsed.queryType)
        assertEquals(1, parsed.queryClass)
    }

    @Test fun buildsNxDomainPreservingIdAndQuestion() {
        val packet = query("blocked.example")
        val parsed = DnsWireCodec.parseSingleQuestion(packet)!!
        val response = DnsWireCodec.buildNxDomainResponse(packet, parsed)
        assertEquals(0x1234, ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff))
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        assertTrue((flags and 0x8000) != 0)
        assertEquals(3, flags and 0x000f)
        assertTrue((flags and 0x0100) != 0) // RD preserved
        assertEquals(1, response[5].toInt())
        assertEquals(0, response[7].toInt())
        assertArrayEquals(packet.copyOfRange(12, packet.size), response.copyOfRange(12, response.size))
    }

    @Test fun buildsServFailResponseWhenUpstreamIsUnavailable() {
        val packet = query("allowed.example")
        val parsed = DnsWireCodec.parseSingleQuestion(packet)!!
        val response = DnsWireCodec.buildServFailResponse(packet, parsed)
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        assertTrue((flags and 0x8000) != 0)
        assertEquals(2, flags and 0x000f)
        assertTrue((flags and 0x0080) != 0) // RA
        assertEquals(0, response[7].toInt()) // no answer
        assertArrayEquals(packet.copyOfRange(12, packet.size), response.copyOfRange(12, response.size))
    }

    @Test fun rejectsResponseAndMultiQuestionPackets() {
        val response = query("ads.example", flags = 0x8180)
        assertNull(DnsWireCodec.parseSingleQuestion(response))
        val multi = query("ads.example")
        multi[5] = 2
        assertNull(DnsWireCodec.parseSingleQuestion(multi))
    }

    @Test fun rejectsCompressedAndTruncatedNames() {
        val compressed = query("ads.example")
        compressed[12] = 0xc0.toByte()
        assertNull(DnsWireCodec.parseSingleQuestion(compressed))
        assertNull(DnsWireCodec.parseSingleQuestion(byteArrayOf(0x12, 0x34, 0, 0)))
    }

    @Test fun acceptsEdnsOptRecordAndPreservesItInBlockedResponse() {
        val base = query("ads.example")
        val packet = base.copyOf(base.size + 11)
        packet[11] = 1 // ARCOUNT
        val offset = base.size
        packet[offset] = 0 // root owner name
        packet[offset + 1] = 0
        packet[offset + 2] = 41 // OPT
        packet[offset + 3] = 0x04
        packet[offset + 4] = 0xd0.toByte() // advertised UDP payload 1232
        packet[offset + 9] = 0
        packet[offset + 10] = 0 // empty OPT data

        val parsed = DnsWireCodec.parseSingleQuestion(packet)!!
        assertEquals("ads.example", parsed.hostname)
        val response = DnsWireCodec.buildNxDomainResponse(packet, parsed)
        assertEquals(1, response[11].toInt() and 0xff)
        assertArrayEquals(packet.copyOfRange(12, packet.size), response.copyOfRange(12, response.size))
    }

    @Test fun malformedHostnameIsNotTreatedAsBlocked() {
        val packet = query("ads.example")
        packet[13] = 0 // empty label in middle
        assertNull(DnsWireCodec.parseSingleQuestion(packet))
        assertFalse(DomainBlocklist.fromLines(listOf("ads.example")).isBlocked(""))
    }
}
