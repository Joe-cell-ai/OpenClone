package org.openclone.app.clone

/** Validation rules for user-visible signing identity names. */
internal object SigningIdentityAlias {
    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}")

    fun validate(alias: String) {
        require(pattern.matches(alias)) {
            "Use a signing identity name with 1–64 letters, numbers, spaces, dots, underscores, or hyphens."
        }
    }
}
