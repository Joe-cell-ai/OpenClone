package org.openclone.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class MiRemoteProfile(val id: String, val name: String, val category: String, val room: String, val favorite: Boolean)

private object MiRemoteSoftwareStore {
    private const val PREFS = "mi_remote_software"
    private const val KEY = "profiles"
    fun load(context: Context): List<MiRemoteProfile> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList { for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(MiRemoteProfile(o.getString("id"), o.getString("name"), o.getString("category"), o.optString("room"), o.optBoolean("favorite")))
        } }
    }.getOrDefault(emptyList())
    fun save(context: Context, profiles: List<MiRemoteProfile>) {
        val array = JSONArray()
        profiles.forEach { p -> array.put(JSONObject().put("id", p.id).put("name", p.name)
            .put("category", p.category).put("room", p.room).put("favorite", p.favorite)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit())
    }
}

@Composable
fun MiRemoteSoftwareScreen() {
    val context = LocalContext.current
    var profiles by remember(context) { mutableStateOf(MiRemoteSoftwareStore.load(context)) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("TV / Media") }
    var room by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Create software remote profiles, organise rooms and save favourite control layouts.") }
    val selected = profiles.firstOrNull { it.id == selectedId }
    fun persist(next: List<MiRemoteProfile>) {
        runCatching { MiRemoteSoftwareStore.save(context, next); profiles = next }
            .onFailure { status = it.message ?: "Could not save remote profile." }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mi Remote Studio", style = MaterialTheme.typography.headlineSmall)
                Text("Software-only remote workspace: create profiles, organise rooms, pin favourites and arrange virtual control layouts. No IR, Bluetooth, device discovery or external hardware is used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(48) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Remote profile name") })
                OutlinedTextField(category, { category = it.take(32) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Category (TV, media, presentation, app)") })
                OutlinedTextField(room, { room = it.take(48) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Room / workspace") })
                Button(enabled = name.isNotBlank(), onClick = {
                    val p = MiRemoteProfile(java.util.UUID.randomUUID().toString(), name.trim(), category.trim().ifBlank { "General" }, room.trim(), false)
                    persist(profiles + p); name = ""; status = "Remote profile saved on this device."
                }, modifier = Modifier.fillMaxWidth()) { Text("＋ Create remote profile") }
            } }
        }
        item { Text("Your remotes · ${profiles.size}", style = MaterialTheme.typography.titleMedium) }
        if (profiles.isEmpty()) item { Text("No profiles yet. Create your first software remote above.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(profiles, key = { it.id }) { profile ->
            Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${profile.category} · ${profile.room.ifBlank { "No room assigned" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { persist(profiles.map { if (it.id == profile.id) it.copy(favorite = !it.favorite) else it }) }) { Text(if (profile.favorite) "★ Saved" else "☆ Favorite") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selectedId = if (selectedId == profile.id) null else profile.id }) { Text(if (selectedId == profile.id) "Close layout" else "Open layout") }
                    TextButton(onClick = { persist(profiles.filterNot { it.id == profile.id }); if (selectedId == profile.id) selectedId = null }) { Text("Delete") }
                }
                if (selectedId == profile.id) {
                    Text("Virtual control layout", style = MaterialTheme.typography.titleSmall)
                    listOf(listOf("Power", "Home", "Back"), listOf("Volume −", "Mute", "Volume +"), listOf("←", "OK", "→"), listOf("↑", "↓", "Menu")).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { action ->
                                Button(onClick = { status = "$action selected for ${profile.name}. This software-only build stores the layout; no physical-device command is transmitted." }, modifier = Modifier.weight(1f)) { Text(action) }
                            }
                        }
                    }
                    Text("Edit room", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(profile.room, { value ->
                        persist(profiles.map { if (it.id == profile.id) it.copy(room = value.take(48)) else it })
                    }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Room") })
                }
            } }
        }
        item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
