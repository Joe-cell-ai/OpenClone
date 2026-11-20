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
            val routineCeiling = limitBytes
            val totalCeiling = limitBytes + reserveBytes
            var current = file.length()
            if (!essential && current >= routineCeiling) {
                if (!noticeWritten && current < totalCeiling) {
                    appendBounded("LOG LIMIT REACHED ($limitBytes bytes): routine messages are dropped; essential failure details may use the reserve.", totalCeiling - current)
                    noticeWritten = true
                }
                return
            }
            val ceiling = if (essential) totalCeiling else routineCeiling
            val remaining = ceiling - current
            if (remaining > 0L) appendBounded(message, remaining)
        }
    }

    private fun appendBounded(message: String, remaining: Long) {
        if (remaining <= 0L) return
        val line = ("[${Instant.now()}] " + message + "\n").toByteArray(Charsets.UTF_8)
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
