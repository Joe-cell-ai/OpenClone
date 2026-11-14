package org.openclone.app.data

import android.content.Context
import org.openclone.app.clone.CloneBuildResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Persists metadata for generated clone APK sets; APK bytes remain in app-private storage. */
data class CloneBuildHistoryEntry(
    val originalPackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val apkPaths: List<String>,
    val obbPaths: List<String>,
    val iconBadgeApplied: Boolean,
    val customIconApplied: Boolean,
    val createdAtMillis: Long
) {
    fun toBuildResult(): CloneBuildResult = CloneBuildResult(
        originalPackage = originalPackage,
        clonePackage = clonePackage,
        cloneLabel = cloneLabel,
        apks = apkPaths.map(::File),
        iconBadgeApplied = iconBadgeApplied,
        customIconApplied = customIconApplied,
        obbFiles = obbPaths.map(::File)
    )

    val filesAvailable: Boolean
        get() = apkPaths.isNotEmpty() && apkPaths.all { File(it).isFile } && obbPaths.all { File(it).isFile }
}

class CloneBuildHistoryRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun getAll(): List<CloneBuildHistoryEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val entry = CloneBuildHistoryEntry(
                        originalPackage = item.getString("originalPackage"),
                        clonePackage = item.getString("clonePackage"),
                        cloneLabel = item.getString("cloneLabel"),
                        apkPaths = item.getJSONArray("apkPaths").toStringList(),
                        obbPaths = item.optJSONArray("obbPaths")?.toStringList().orEmpty(),
                        iconBadgeApplied = item.optBoolean("iconBadgeApplied"),
                        customIconApplied = item.optBoolean("customIconApplied"),
                        createdAtMillis = item.optLong("createdAtMillis")
                    )
                    add(entry)
                }
            }.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    fun add(result: CloneBuildResult): List<CloneBuildHistoryEntry> {
        val entry = CloneBuildHistoryEntry(
            originalPackage = result.originalPackage,
            clonePackage = result.clonePackage,
            cloneLabel = result.cloneLabel,
            apkPaths = result.apks.map { it.absolutePath },
            obbPaths = result.obbFiles.map { it.absolutePath },
            iconBadgeApplied = result.iconBadgeApplied,
            customIconApplied = result.customIconApplied,
            createdAtMillis = System.currentTimeMillis()
        )
        val updated = (listOf(entry) + getAll()).distinctBy { it.clonePackage to it.createdAtMillis }.take(MAX_ENTRIES)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().apply {
                put("originalPackage", item.originalPackage)
                put("clonePackage", item.clonePackage)
                put("cloneLabel", item.cloneLabel)
                put("apkPaths", JSONArray(item.apkPaths))
                put("obbPaths", JSONArray(item.obbPaths))
                put("iconBadgeApplied", item.iconBadgeApplied)
                put("customIconApplied", item.customIconApplied)
                put("createdAtMillis", item.createdAtMillis)
            })
        }
        check(preferences.edit().putString(KEY_ENTRIES, array.toString()).commit()) {
            "Could not save clone build history."
        }
        return updated
    }

    fun remove(clonePackage: String, createdAtMillis: Long): List<CloneBuildHistoryEntry> {
        val updated = getAll().filterNot { it.clonePackage == clonePackage && it.createdAtMillis == createdAtMillis }
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().apply {
                put("originalPackage", item.originalPackage)
                put("clonePackage", item.clonePackage)
                put("cloneLabel", item.cloneLabel)
                put("apkPaths", JSONArray(item.apkPaths))
                put("obbPaths", JSONArray(item.obbPaths))
                put("iconBadgeApplied", item.iconBadgeApplied)
                put("customIconApplied", item.customIconApplied)
                put("createdAtMillis", item.createdAtMillis)
            })
        }
        check(preferences.edit().putString(KEY_ENTRIES, array.toString()).commit()) {
            "Could not update clone build history."
        }
        return updated
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }

    private companion object {
        const val PREFERENCES = "openclone_build_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 30
    }
}
