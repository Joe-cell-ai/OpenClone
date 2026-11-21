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

    /**
     * WhatsApp's registration flow launches internal activities through explicit component
     * intents. Leaving those package identities unchanged makes Android resolve them against
     * the original installation, where non-exported activities cannot be launched by the clone.
     * Therefore this rewrite is mandatory for WhatsApp and cannot be disabled by a stale or
     * unchecked UI/worker override. Other apps continue to respect the caller's choice.
     */
    fun resolveEnabled(originalPackage: String, requestedOverride: Boolean?): Boolean =
        if (originalPackage == WHATSAPP_PACKAGE) true
        else requestedOverride ?: defaultEnabledFor(originalPackage)

    const val WHATSAPP_PACKAGE = "com.whatsapp"
}
