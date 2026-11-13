package org.openclone.app.data

/**
 * Heuristic pre-build checks for common package/signature-bound integrations.
 *
 * These checks only inspect declared package metadata. They cannot prove that an app will
 * work after cloning, and absence of a warning is not a compatibility guarantee.
 */
data class CloneCompatibilityReport(
    val findings: List<String>,
    val limitations: List<String> = listOf(
        "The clone receives a new package ID and signing certificate. Apps that validate either identity may refuse to run.",
        "Google Play services, Firebase, OAuth, maps, licensing, DRM, anti-cheat, and server-side registration may be tied to the original app identity.",
        "Original app data, accounts, and sign-in sessions are not copied into the clone."
    )
) {
    val hasFindings: Boolean get() = findings.isNotEmpty()
}

object CloneCompatibilityAnalyzer {
    private val firebasePermissions = setOf(
        "com.google.android.c2dm.permission.RECEIVE",
        "com.google.android.c2dm.permission.SEND"
    )
    private val playServicesPermissions = setOf(
        "com.google.android.providers.gsf.permission.READ_GSERVICES",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE"
    )

    fun analyze(
        packageName: String,
        requestedPermissions: Collection<String>,
        metadataKeys: Collection<String> = emptyList()
    ): CloneCompatibilityReport {
        val permissions = requestedPermissions.toSet()
        val metadata = metadataKeys.toSet()
        val findings = buildList {
            if (permissions.any { it in firebasePermissions } ||
                metadata.any { it.startsWith("com.google.firebase.") }) {
                add("Firebase/Cloud Messaging declarations detected. Push registration may be tied to the original package ID.")
            }
            if (permissions.any { it in playServicesPermissions } ||
                "com.google.android.geo.API_KEY" in metadata) {
                add("Google services integration indicators detected. Maps, install-referrer, or other package/certificate-bound services may not work in the clone.")
            }
            if (packageName.startsWith("com.google.android.") ||
                packageName.startsWith("com.android.")) {
                add("This appears to be an Android/platform package. Cloning system components is not generally supported and may destabilize the device.")
            }
        }.distinct()
        return CloneCompatibilityReport(findings)
    }
}
