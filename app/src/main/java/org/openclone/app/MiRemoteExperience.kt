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
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import org.json.JSONArray
import org.json.JSONObject

private data class SavedRemoteScene(
    val id: String,
    val name: String,
    val room: String,
    val profileId: String,
    val commandNames: List<String>
)

private object MiRemoteFeatureStore {
    private const val PREFS = "openclone_mi_remote_features"
    private const val FAVORITES = "favorite_profile_ids"
    private const val ROOMS = "profile_rooms"
    private const val SCENES = "saved_scenes"

    fun favorites(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(FAVORITES, emptySet())?.toSet().orEmpty()

    fun setFavorite(context: Context, profileId: String, favorite: Boolean): Set<String> {
        val updated = favorites(context).toMutableSet().apply {
            if (favorite) add(profileId) else remove(profileId)
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(FAVORITES, updated).commit()) {
            "Could not save favorite remote."
        }
        return updated
    }

    fun rooms(context: Context): Map<String, String> = runCatching {
        val json = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ROOMS, "{}") ?: "{}")
        buildMap { json.keys().forEach { key -> put(key, json.optString(key).take(48)) } }
    }.getOrDefault(emptyMap())

    fun setRoom(context: Context, profileId: String, room: String): Map<String, String> {
        val updated = rooms(context).toMutableMap()
        if (room.isBlank()) updated.remove(profileId) else updated[profileId] = room.trim().take(48)
        val json = JSONObject().apply { updated.forEach { (key, value) -> put(key, value) } }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ROOMS, json.toString()).commit()) {
            "Could not save remote room."
        }
        return updated
    }

    fun scenes(context: Context): List<SavedRemoteScene> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SCENES, "[]") ?: "[]")
        require(array.length() <= 20)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val names = item.getJSONArray("commands")
                require(names.length() in 1..20)
                add(SavedRemoteScene(
                    item.getString("id"),
                    item.getString("name").take(48),
                    item.optString("room").take(48),
                    item.getString("profileId"),
                    (0 until names.length()).map { names.getString(it).take(80) }
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun saveScene(context: Context, scene: SavedRemoteScene): List<SavedRemoteScene> {
        require(scene.name.isNotBlank() && scene.name.length <= 48)
        require(scene.commandNames.isNotEmpty() && scene.commandNames.size <= 20)
        val updated = (scenes(context).filterNot { it.id == scene.id } + scene).takeLast(20)
        val json = JSONArray().apply {
            updated.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id); put("name", item.name); put("room", item.room)
                    put("profileId", item.profileId)
                    put("commands", JSONArray().apply { item.commandNames.forEach(::put) })
                })
            }
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SCENES, json.toString()).commit()) {
            "Could not save remote scene."
        }
        return updated
    }

    fun deleteScene(context: Context, id: String): List<SavedRemoteScene> {
        val updated = scenes(context).filterNot { it.id == id }
        val json = JSONArray().apply {
            updated.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id); put("name", item.name); put("room", item.room)
                    put("profileId", item.profileId)
                    put("commands", JSONArray().apply { item.commandNames.forEach(::put) })
                })
            }
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SCENES, json.toString()).commit()) {
            "Could not delete remote scene."
        }
        return updated
    }
}

/** Mi Remote-style layouts only enable buttons when a matching real IR command exists in the imported profile. */
@Composable
fun MiRemoteExperienceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember(context) { mutableStateOf(InfraredProfileStore.load(context)) }
    var favorites by remember(context) { mutableStateOf(MiRemoteFeatureStore.favorites(context)) }
    var rooms by remember(context) { mutableStateOf(MiRemoteFeatureStore.rooms(context)) }
    var scenes by remember(context) { mutableStateOf(MiRemoteFeatureStore.scenes(context)) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var roomDraft by remember { mutableStateOf("") }
    var sceneName by remember { mutableStateOf("") }
    var sceneRoom by remember { mutableStateOf("") }
    var selectedSceneCommands by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf("Choose an imported remote to open its appliance layout. Buttons without a matching code stay disabled.") }
    val selected = profiles.firstOrNull { it.id == selectedId }
    val irAvailable = remember(context) { InfraredRemote.isAvailable(context) }

    fun commandFor(profile: SavedInfraredProfile, label: String): NamedInfraredCommand? {
        val aliases = when (label) {
            "Power" -> setOf("power", "poweron", "poweroff", "onoff", "standby")
            "Volume +" -> setOf("volumeup", "volup", "volumeplus")
            "Volume −" -> setOf("volumedown", "voldown", "volumeminus")
            "Mute" -> setOf("mute", "volumemute")
            "Channel +" -> setOf("channelup", "chanup", "programup")
            "Channel −" -> setOf("channeldown", "chandown", "programdown")
            "Home" -> setOf("home", "menu")
            "Back" -> setOf("back", "return", "exit")
            "OK" -> setOf("ok", "enter", "select")
            "Up" -> setOf("up", "arrowup")
            "Down" -> setOf("down", "arrowdown")
            "Left" -> setOf("left", "arrowleft")
            "Right" -> setOf("right", "arrowright")
            "Mode" -> setOf("mode", "cool", "heat", "auto", "dry")
            "Temperature +" -> setOf("tempup", "temperatureup", "up")
            "Temperature −" -> setOf("tempdown", "temperaturedown", "down")
            "Fan" -> setOf("fan", "fanspeed", "fanmode")
            "Swing" -> setOf("swing", "vane", "airdirection")
            else -> setOf(label.lowercase().filter(Char::isLetterOrDigit))
        }
        return profile.commands.firstOrNull { item ->
            val normalized = item.name.lowercase().filter(Char::isLetterOrDigit)
            normalized in aliases || aliases.any { alias -> normalized == alias }
        }
    }

    fun send(profile: SavedInfraredProfile, item: NamedInfraredCommand) {
        runCatching { InfraredRemote.transmit(context, item.command) }
            .onSuccess { message = "Sent ${item.name}. Check that the appliance responded." }
            .onFailure { message = it.message ?: "Could not transmit this command." }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Mi Remote-style appliance controls", style = MaterialTheme.typography.titleMedium)
            Text(
                "TV, AC and set-top-box layouts, favorites, room labels and saved scenes. This uses only commands present in your imported profile; it does not invent or claim verified manufacturer codes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (profiles.isEmpty()) {
                Text("Import and save an IR profile above to create a remote layout.")
            } else {
                profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${profile.brand} · ${profile.model}", style = MaterialTheme.typography.titleSmall)
                            Text("${profile.category.displayName} · ${rooms[profile.id] ?: "No room"} · ${profile.commands.size} commands",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            selectedId = if (selectedId == profile.id) null else profile.id
                            roomDraft = rooms[profile.id].orEmpty()
                            selectedSceneCommands = emptySet()
                        }) { Text(if (selectedId == profile.id) "Close" else "Open") }
                        TextButton(onClick = {
                            runCatching { favorites = MiRemoteFeatureStore.setFavorite(context, profile.id, profile.id !in favorites) }
                                .onFailure { message = it.message ?: "Could not update favorite." }
                        }) { Text(if (profile.id in favorites) "★" else "☆") }
                    }
                    if (selectedId == profile.id) {
                        OutlinedTextField(
                            value = roomDraft, onValueChange = { roomDraft = it.take(48) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Room (e.g. Living room)") }
                        )
                        Button(onClick = {
                            runCatching { rooms = MiRemoteFeatureStore.setRoom(context, profile.id, roomDraft) }
                                .onFailure { message = it.message ?: "Could not save room." }
                        }) { Text("Save room") }

                        val isTv = profile.category.name in setOf("TELEVISION", "SET_TOP_BOX", "PROJECTOR", "AUDIO")
                        val isAc = profile.category.name == "AIR_CONDITIONER"
                        if (isTv) {
                            Text("TV / media remote", style = MaterialTheme.typography.titleSmall)
                            listOf(listOf("Power", "Mute", "Home", "Back"), listOf("Volume +", "Volume −", "Channel +", "Channel −"),
                                listOf("Up", "Left", "OK", "Right", "Down")).forEach { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    group.forEach { label ->
                                        val command = commandFor(profile, label)
                                        Button(
                                            enabled = irAvailable && command != null,
                                            onClick = { command?.let { send(profile, it) } },
                                            modifier = Modifier.weight(1f)
                                        ) { Text(label) }
                                    }
                                }
                            }
                        } else if (isAc) {
                            Text("Air conditioner remote", style = MaterialTheme.typography.titleSmall)
                            listOf(listOf("Power", "Mode", "Fan", "Swing"), listOf("Temperature +", "Temperature −")).forEach { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    group.forEach { label ->
                                        val command = commandFor(profile, label)
                                        Button(
                                            enabled = irAvailable && command != null,
                                            onClick = { command?.let { send(profile, it) } },
                                            modifier = Modifier.weight(1f)
                                        ) { Text(label) }
                                    }
                                }
                            }
                            Text("AC display values and temperature presets are unavailable unless the imported code set contains separate verified state commands.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("All imported commands", style = MaterialTheme.typography.titleSmall)
                        profile.commands.forEach { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.name.replace('_', ' '), modifier = Modifier.weight(1f))
                                Button(enabled = irAvailable, onClick = { send(profile, item) }) { Text("Send") }
                                Checkbox(
                                    checked = item.name in selectedSceneCommands,
                                    onCheckedChange = { checked ->
                                        selectedSceneCommands = if (checked) selectedSceneCommands + item.name else selectedSceneCommands - item.name
                                    }
                                )
                            }
                        }
                        OutlinedTextField(value = sceneName, onValueChange = { sceneName = it.take(48) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("New scene name") })
                        OutlinedTextField(value = sceneRoom, onValueChange = { sceneRoom = it.take(48) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Scene room (optional)") })
                        Button(
                            enabled = sceneName.isNotBlank() && selectedSceneCommands.isNotEmpty() && selectedSceneCommands.size <= 20,
                            onClick = {
                                runCatching {
                                    val scene = SavedRemoteScene(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = sceneName.trim(),
                                        room = sceneRoom.trim(),
                                        profileId = profile.id,
                                        commandNames = profile.commands.map { it.name }.filter { it in selectedSceneCommands }
                                    )
                                    scenes = MiRemoteFeatureStore.saveScene(context, scene)
                                    sceneName = ""; sceneRoom = ""; selectedSceneCommands = emptySet()
                                    message = "Scene saved. It will send only the selected commands from this imported profile."
                                }.onFailure { message = it.message ?: "Could not save scene." }
                            }
                        ) { Text("Save scene from selected buttons") }
                    }
                }
            }
            if (scenes.isNotEmpty()) {
                Text("Saved scenes", style = MaterialTheme.typography.titleSmall)
                scenes.forEach { scene ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(scene.name, style = MaterialTheme.typography.titleSmall)
                            Text("${scene.room.ifBlank { "No room" }} · ${scene.commandNames.size} commands",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            enabled = irAvailable && profiles.any { it.id == scene.profileId },
                            onClick = {
                                val profile = profiles.firstOrNull { it.id == scene.profileId }
                                val commands = profile?.commands?.filter { it.name in scene.commandNames }.orEmpty()
                                if (commands.size != scene.commandNames.size) {
                                    message = "Scene is incomplete: one or more saved commands are missing."
                                } else {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                commands.forEachIndexed { index, command ->
                                                    InfraredRemote.transmit(context, command.command)
                                                    if (index < commands.lastIndex) Thread.sleep(180)
                                                }
                                            }
                                        }.onSuccess { message = "Scene ${scene.name} sent. Check each appliance response." }
                                            .onFailure { message = it.message ?: "Scene transmission failed." }
                                    }
                                }
                            }
                        ) { Text("Run") }
                        TextButton(onClick = {
                            runCatching { scenes = MiRemoteFeatureStore.deleteScene(context, scene.id) }
                                .onFailure { message = it.message ?: "Could not delete scene." }
                        }) { Text("Delete") }
                    }
                }
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!irAvailable) Text("No built-in IR transmitter detected. A compatible external IR adapter is required to send commands.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
