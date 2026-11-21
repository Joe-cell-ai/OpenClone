package org.openclone.app.clone

/**
 * DEX package-string rewriting is intentionally opt-in for most apps because some apps
 * depend on their original package identity. WhatsApp uses explicit flattened component
 * intents during registration, so cloned builds need the targeted package rewrite enabled
 * by default to direct those intents to the clone package.
 */
object DexRewritePolicy {
    fun defaultEnabledFor(originalPackage: String): Boolean =
        originalPackage == WHATSAPP_PACKAGE

    /** Uses the app-specific default only when the build request did not supply an override. */
    fun resolveEnabled(originalPackage: String, requestedOverride: Boolean?): Boolean =
        requestedOverride ?: defaultEnabledFor(originalPackage)

    const val WHATSAPP_PACKAGE = "com.whatsapp"
}
