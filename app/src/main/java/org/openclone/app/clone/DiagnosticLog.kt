package org.openclone.app.clone

import java.io.File
import java.time.Instant

/** Thread-safe append-only diagnostic log with bounded routine and essential reserves. */
class DiagnosticLog(
    private val file: File,
    private val limitBytes: Long = DEFAULT_LIMIT_BYTES,
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES
) {
    private var noticeWritten = false

    @Synchronized
    fun write(message: String, essential: Boolean = false) {
        runCatching {
            val routineCeiling = limitBytes.coerceAtLeast(0L)
            val totalCeiling = routineCeiling + reserveBytes.coerceAtLeast(0L)
            val current = file.length()

            if (essential) {
                val remaining = (totalCeiling - current).coerceAtLeast(0L)
                appendBounded(message, remaining)
                return
            }

            // Routine diagnostics, including the limit notice, must never consume
            // the reserved space intended for failure details.
            val remaining = (routineCeiling - current).coerceAtLeast(0L)
            if (remaining == 0L) return

            val lineSize = formattedLine(message).size.toLong()
            if (lineSize > remaining && !noticeWritten) {
                appendBounded(
                    "LOG LIMIT REACHED ($routineCeiling bytes): routine messages are dropped; essential failure details may use the reserve.",
                    remaining
                )
                noticeWritten = true
            } else if (lineSize <= remaining) {
                appendBounded(message, remaining)
            }
        }
    }

    private fun formattedLine(message: String): ByteArray =
        ("[${Instant.now()}] " + message + "\\n").toByteArray(Charsets.UTF_8)

    private fun appendBounded(message: String, remaining: Long) {
        if (remaining <= 0L) return
        val line = formattedLine(message)
        val count = minOf(line.size.toLong(), remaining, Int.MAX_VALUE.toLong()).toInt()
        if (count <= 0) return
        file.parentFile?.mkdirs()
        file.appendBytes(if (count == line.size) line else line.copyOf(count))
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES = 1_500_000L
        const val DEFAULT_RESERVE_BYTES = 64_000L
    }
}
