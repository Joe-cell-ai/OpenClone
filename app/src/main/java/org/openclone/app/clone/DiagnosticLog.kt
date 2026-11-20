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
            val ceiling = if (essential) limitBytes + reserveBytes else limitBytes
            val remaining = ceiling - file.length()
            if (remaining <= 0L) return
            val prefix = "[${Instant.now()}] "
            val notice = "LOG LIMIT REACHED ($limitBytes bytes): additional routine messages are dropped."
            val text = if (!essential && !noticeWritten && remaining > 0L) notice else message
            val line = (prefix + text + "\n").toByteArray(Charsets.UTF_8)
            val bounded = if (line.size.toLong() <= remaining) line else line.copyOf(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (bounded.isNotEmpty()) {
                file.parentFile?.mkdirs()
                file.appendBytes(bounded)
                if (text == notice) noticeWritten = true
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES = 1_500_000L
        const val DEFAULT_RESERVE_BYTES = 64_000L
    }
}
