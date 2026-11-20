package org.openclone.app.clone

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    private fun tempFile(): File = File(Files.createTempDirectory("openclone-log-").toFile(), "build.txt")

    @Test fun routineAndEssentialWritesStayWithinTheirBudgets() {
        val file = tempFile()
        val log = DiagnosticLog(file, limitBytes = 2_000L, reserveBytes = 500L)
        repeat(100) { log.write("routine-$it " + "x".repeat(100)) }
        val afterRoutine = file.length()
        assertTrue(afterRoutine <= 2_000L)
        repeat(100) { log.write("failure-$it " + "y".repeat(100), essential = true) }
        assertTrue(file.length() <= 2_500L)
        assertTrue(file.readText().contains("failure-"))
        assertFalse(file.readText().contains("failure-99"))
    }

    @Test fun concurrentWritersDoNotInterleaveLines() {
        val file = tempFile()
        val log = DiagnosticLog(file, limitBytes = 300_000L, reserveBytes = 10_000L)
        val threads = (1..8).map { id -> Thread { repeat(200) { log.write("thread-$id-line-$it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(file.readLines().all { it.startsWith("[") && it.contains("] thread-") || it.contains("LOG LIMIT REACHED") })
        assertTrue(file.length() <= 300_000L)
    }

    @Test fun fileWriteFailuresAreContained() {
        val blocker = tempFile()
        blocker.writeText("file")
        DiagnosticLog(File(blocker, "child.txt")).write("ignored")
    }
}
