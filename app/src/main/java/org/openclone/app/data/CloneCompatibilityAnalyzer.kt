package org.openclone.app.data

/**
 * Heuristic pre-build checks for common package/signature-bound integrations.
 *
 * These checks inspect only declarations in the APK manifest. They cannot prove that an app
 * will work after cloning; absence of a warning is not a compatibility guarantee.
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
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "com.android.vending.CHECK_LICENSE",
        "com.google.android.play.core.integrity.BIND_INTEGRITY_SERVICE"
    )

    fun analyze(
        packageName: String,
        requestedPermissions: Collection<String>,
        metadataKeys: Collection<String> = emptyList(),
        componentNames: Collection<String> = emptyList(),
        providerAuthorities: Collection<String> = emptyList(),
        sharedUserId: String? = null
    ): CloneCompatibilityReport {
        val permissions = requestedPermissions.toSet()
        val metadata = metadataKeys.toSet()
        val components = componentNames.map { it.lowercase() }
        val authorities = providerAuthorities.map { it.lowercase() }
        val findings = buildList {
            if (permissions.any { it in firebasePermissions } ||
                metadata.any { it.startsWith("com.google.firebase.") } ||
                components.any { "firebase" in it && ("messaging" in it || "instanceid" in it) } ||
                authorities.any { "firebase" in it }) {
                add("Firebase/Cloud Messaging declarations detected. Push registration or Firebase initialization may be tied to the original package ID.")
            }
            if (permissions.any { it in playServicesPermissions } ||
                metadata.any {
                    it == "com.google.android.geo.API_KEY" ||
                        it == "com.google.android.gms.version" ||
                        it.startsWith("com.google.android.gms.")
                }) {
                add("Google services integration indicators detected. Maps, install-referrer, or other package/certificate-bound services may not work in the clone.")
            }
            if (components.any { "google" in it && ("auth" in it || "signin" in it || "credential" in it) }) {
                add("Google sign-in or authentication components detected. OAuth client registration commonly depends on the original package ID and signing certificate.")
            }
            if (!sharedUserId.isNullOrBlank()) {
                add("The APK declares sharedUserId ($sharedUserId). Android shared-user signing and install constraints can prevent a renamed clone from installing or behaving correctly.")
            }
            if (components.any { "integrity" in it || "licensing" in it } ||
                metadata.any { "integrity" in it || "licensing" in it }) {
                add("Play Integrity or licensing integration indicators detected. OpenClone does not bypass these protections; the app may reject a re-signed or renamed clone.")
            }
            if (packageName.startsWith("com.google.android.") ||
                packageName.startsWith("com.android.")) {
                add("This appears to be an Android/platform package. Cloning system components is not generally supported and may destabilize the device.")
            }
        }.distinct()
        return CloneCompatibilityReport(findings)
    }
}
