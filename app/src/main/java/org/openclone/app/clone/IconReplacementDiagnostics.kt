package org.openclone.app.clone

import java.util.Locale

/** File-backed icon resource families currently recognized by the replacement pipeline. */
enum class IconResourceKind {
    PNG,
    XML,
    OTHER;

    companion object {
        fun fromPath(path: String): IconResourceKind {
            return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "png" -> PNG
                "xml" -> XML
                else -> OTHER
            }
        }
    }
}

enum class IconReplacementAction(val label: String) {
    BADGE("badge"),
    CUSTOM_ICON("custom-icon")
}

enum class IconDiagnosticStatus(val label: String) {
    APPLIED("applied"),
    UNSUPPORTED("unsupported"),
    FAILED("failed")
}

/** One bounded, actionable record for a resolved icon resource or density variant. */
data class IconReplacementDiagnostic(
    val action: IconReplacementAction,
    val resourceId: Int,
    val resourcePath: String?,
    val densityDpi: Int,
    val status: IconDiagnosticStatus,
    val detail: String
) {
    fun toLogLine(): String {
        val path = resourcePath?.takeIf { it.isNotBlank() } ?: "<unresolved>"
        val id = "0x" + Integer.toHexString(resourceId).padStart(8, '0')
        val density = if (densityDpi <= 0) "default" else densityDpi.toString() + "dpi"
        val compactDetail = detail.replace('\n', ' ').replace('\r', ' ').take(240)
        return "ICON " + action.label + " " + status.label +
            ": resource=" + path + " id=" + id + " density=" + density + "; " + compactDetail
    }
}

/** Result of the icon pass; unsupported source resources remain untouched in the APK. */
data class IconReplacementResult(
    val replacements: Map<String, ByteArray>,
    val badgeApplied: Boolean,
    val customIconApplied: Boolean,
    val diagnostics: List<IconReplacementDiagnostic>,
    val omittedDiagnostics: Int = 0
)
