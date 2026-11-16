package org.openclone.app.smartremote

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-delimited protobuf transport framing used by Android TV Remote v2.
 *
 * This class only frames protobuf payloads. It does not implement pairing,
 * TLS identity verification, message schemas, or command authorization.
 */
object AndroidTvRemoteFraming {
    const val MAX_FRAME_SIZE: Int = 1 shl 20

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Empty protobuf frames are not supported" }
        require(payload.size <= MAX_FRAME_SIZE) { "Frame exceeds the 1 MiB safety limit" }

        var value = payload.size
        val prefix = ArrayList<Byte>(5)
        while ((value and 0x7f.inv()) != 0) {
            prefix.add(((value and 0x7f) or 0x80).toByte())
            value = value ushr 7
        }
        prefix.add(value.toByte())

        return ByteArray(prefix.size + payload.size).also { output ->
            prefix.forEachIndexed { index, byte -> output[index] = byte }
            payload.copyInto(output, prefix.size)
        }
    }

    /** Returns null only when EOF occurs before a new frame starts. */
    fun readFrame(input: InputStream): ByteArray? {
        val first = input.read()
        if (first == -1) return null

        var length = 0
        var shift = 0
        var current = first
        var count = 0
        while (true) {
            count++
            if (count > 5 || (count == 5 && (current and 0xf0) != 0)) {
                throw IllegalArgumentException("Invalid protobuf frame length prefix")
            }
            length = length or ((current and 0x7f) shl shift)
            if ((current and 0x80) == 0) break
            shift += 7
            current = input.read()
            if (current == -1) throw EOFException("Truncated protobuf frame length")
        }

        require(length in 1..MAX_FRAME_SIZE) { "Invalid protobuf frame size: $length" }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read == -1) throw EOFException("Truncated protobuf frame payload")
            if (read == 0) {
                val single = input.read()
                if (single == -1) throw EOFException("Truncated protobuf frame payload")
                payload[offset++] = single.toByte()
            } else {
                offset += read
            }
        }
        return payload
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(encode(payload))
        output.flush()
    }
}
