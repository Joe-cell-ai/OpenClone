package org.openclone.app.clone

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Append-only diagnostics for clone builds; never includes APKs or signing-key material. */
class CloneBuildDiagnostics(context: Context, private val workId: String) {
    private val directory = File(context.filesDir, "openclone/diagnostics").apply { mkdirs() }
    val logFile = File(directory, "clone-$workId.jsonl")

    @Synchronized
    fun record(stage: String, detail: String = "", progress: Int? = null, error: Throwable? = null) {
        runCatching {
            val event = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("stage", stage.take(100))
                .put("detail", detail.take(500))
                .put("progress", progress)
                .put("thread", Thread.currentThread().name)
                .put("error", error?.let { e -> "${e.javaClass.name}: ${e.message}".take(1000) })
            if (error != null) event.put("stack", error.stackTraceToString().take(8000))
            logFile.appendText(event.toString() + "\n")
        }
    }

    fun exportZip(destination: File, currentState: String = "unknown", progress: Int = -1) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            if (logFile.isFile) {
                zip.putNextEntry(ZipEntry("clone-diagnostics.jsonl"))
                logFile.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val device = buildString {
                appendLine("OpenClone clone-build diagnostics")
                appendLine("Work ID: $workId")
                appendLine("State: $currentState")
                appendLine("Progress: $progress")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Build fingerprint: ${Build.FINGERPRINT}")
                appendLine("APK contents, app data, credentials and signing keys are not included.")
            }
            zip.putNextEntry(ZipEntry("device-summary.txt"))
            zip.write(device.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    companion object {
        fun logFile(context: Context, workId: String) =
            File(context.filesDir, "openclone/diagnostics/clone-$workId.jsonl")
    }
}
