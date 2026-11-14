package org.openclone.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CloneProxyAssignment(val clonePackage: String, val profileName: String)

class CloneProxyAssignmentRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openclone_clone_proxy_assignments", Context.MODE_PRIVATE)

    @Synchronized fun getAll(): List<CloneProxyAssignment> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val assignment = CloneProxyAssignment(item.optString("clonePackage"), item.optString("profileName"))
                runCatching { validate(assignment) }.onSuccess { add(assignment) }
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun assign(clonePackage: String, profileName: String) {
        val assignment = CloneProxyAssignment(clonePackage, profileName)
        validate(assignment)
        val updated = (getAll().filterNot { it.clonePackage == clonePackage } + assignment).sortedBy { it.clonePackage }
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().put("clonePackage", item.clonePackage).put("profileName", item.profileName))
        }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "Could not save clone proxy assignment." }
    }

    @Synchronized fun removeClone(clonePackage: String) = write(getAll().filterNot { it.clonePackage == clonePackage })

    @Synchronized fun removeProfile(profileName: String) = write(getAll().filterNot { it.profileName == profileName })

    private fun write(assignments: List<CloneProxyAssignment>) {
        val array = JSONArray()
        assignments.forEach { item -> array.put(JSONObject().put("clonePackage", item.clonePackage).put("profileName", item.profileName)) }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "Could not update clone proxy assignments." }
    }

    companion object {
        private const val KEY = "assignments"
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun validate(assignment: CloneProxyAssignment) {
            require(PACKAGE_PATTERN.matches(assignment.clonePackage)) { "Invalid clone package ID." }
            require(assignment.profileName.isNotBlank() && assignment.profileName.length <= 40) { "Invalid proxy profile name." }
        }
    }
}
