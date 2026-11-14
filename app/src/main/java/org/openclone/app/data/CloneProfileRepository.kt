package org.openclone.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A reusable, non-secret clone configuration. Signing keys, credentials and app data
 * are deliberately excluded from profiles.
 */
data class CloneProfile(
    val id: String,
    val name: String,
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val markIcon: Boolean,
    val badgePosition: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    companion object {
        fun validate(
            name: String,
            originalPackage: String,
            clonePackage: String,
            cloneLabel: String
        ) {
            require(name.trim().isNotEmpty() && name.length <= 60) {
                "Profile name must contain 1–60 characters."
            }
            require(PACKAGE_PATTERN.matches(originalPackage)) {
                "Original package ID is invalid."
            }
            require(PACKAGE_PATTERN.matches(clonePackage)) {
                "Clone package ID is invalid."
            }
            require(originalPackage != clonePackage) {
                "Clone package ID must differ from the original."
            }
            require(cloneLabel.trim().isNotEmpty() && cloneLabel.length <= 100) {
                "Clone label must contain 1–100 characters."
            }
        }

        private val PACKAGE_PATTERN =
            Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/**
 * Stores reusable clone setup only in OpenClone's private preferences.
 * Profiles never contain APK bytes, signing material, passwords or clone app data.
 */
class CloneProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<CloneProfile> {
        val array = runCatching {
            JSONArray(preferences.getString(PROFILES_KEY, "[]"))
        }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { item.toProfile() }.getOrNull()?.let(::add)
            }
        }.sortedByDescending { it.updatedAtMillis }
    }

    @Synchronized
    fun save(
        name: String,
        originalPackage: String,
        clonePackage: String,
        cloneLabel: String,
        markIcon: Boolean,
        badgePosition: String,
        existingId: String? = null
    ): CloneProfile {
        CloneProfile.validate(name, originalPackage, clonePackage, cloneLabel)
        require(badgePosition in BADGE_POSITIONS) { "Badge position is not supported." }

        val now = System.currentTimeMillis()
        val existing = existingId?.let { id -> getAll().firstOrNull { it.id == id } }
        require(existingId == null || existing != null) { "The profile no longer exists." }
        val profile = CloneProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            originalPackage = originalPackage,
            clonePackage = clonePackage,
            cloneLabel = cloneLabel.trim(),
            markIcon = markIcon,
            badgePosition = badgePosition,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
        val profiles = getAll().filterNot { it.id == profile.id } + profile
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(PROFILES_KEY, array.toString()).commit()) {
            "Could not save the clone profile."
        }
        return profile
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val profiles = getAll()
        if (profiles.none { it.id == id }) return false
        val array = JSONArray()
        profiles.filterNot { it.id == id }.forEach { array.put(it.toJson()) }
        return preferences.edit().putString(PROFILES_KEY, array.toString()).commit()
    }

    private fun CloneProfile.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("originalPackage", originalPackage)
        put("clonePackage", clonePackage)
        put("cloneLabel", cloneLabel)
        put("markIcon", markIcon)
        put("badgePosition", badgePosition)
        put("createdAtMillis", createdAtMillis)
        put("updatedAtMillis", updatedAtMillis)
    }

    private fun JSONObject.toProfile() = CloneProfile(
        id = getString("id"),
        name = getString("name"),
        originalPackage = getString("originalPackage"),
        clonePackage = getString("clonePackage"),
        cloneLabel = getString("cloneLabel"),
        markIcon = optBoolean("markIcon", true),
        badgePosition = optString("badgePosition", "BOTTOM_RIGHT")
            .takeIf { it in BADGE_POSITIONS } ?: "BOTTOM_RIGHT",
        createdAtMillis = optLong("createdAtMillis"),
        updatedAtMillis = optLong("updatedAtMillis")
    )

    private companion object {
        const val PREFERENCES = "openclone_clone_profiles"
        const val PROFILES_KEY = "profiles"
        val BADGE_POSITIONS = setOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")
    }
}
