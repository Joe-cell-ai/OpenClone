package org.openclone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Offline, user-imported IR remote. A saved profile is explicitly not a verified
 * manufacturer profile: users must test each button against their exact appliance.
 */
data class SavedInfraredProfile(
    val id: String,
    val name: String,
    val brand: String,
    val model: String,
    val category: ApplianceCategory,
    val savedAtMillis: Long,
    val commands: List<NamedInfraredCommand>
)

object InfraredProfileCodec {
    const val MAX_PROFILES = 40
    const val MAX_COMMANDS_PER_PROFILE = 128
    const val MAX_ENCODED_BYTES = 512 * 1024

    fun encode(profiles: List<SavedInfraredProfile>): String {
        require(profiles.size <= MAX_PROFILES) { "Too many saved IR profiles." }
        val root = JSONArray()
        profiles.forEach { profile ->
            validate(profile)
            val commands = JSONArray()
            profile.commands.forEach { item ->
                val timings = JSONArray()
                item.command.timingsMicros.forEach { timings.put(it) }
                commands.put(JSONObject().apply {
                    put("name", item.name)
                    put("carrierHz", item.command.carrierHz)
                    put("timingsMicros", timings)
                })
            }
            root.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("brand", profile.brand)
                put("model", profile.model)
                put("category", profile.category.name)
                put("savedAtMillis", profile.savedAtMillis)
                put("source", "user-imported-flipper-ir")
                put("commands", commands)
            })
        }
        val encoded = root.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Saved IR profiles exceed the 512 KB storage limit."
        }
        return encoded
    }

    fun decode(encoded: String): List<SavedInfraredProfile> {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Saved IR profile data exceeds the 512 KB limit."
        }
        val root = JSONArray(encoded)
        require(root.length() <= MAX_PROFILES) { "Too many saved IR profiles." }
        val profiles = buildList {
            for (index in 0 until root.length()) {
                val item = root.getJSONObject(index)
                require(item.optString("source") == "user-imported-flipper-ir") {
                    "Unsupported IR profile source."
                }
                val commandArray = item.getJSONArray("commands")
                require(commandArray.length() in 1..MAX_COMMANDS_PER_PROFILE) {
                    "An IR profile must contain 1–128 commands."
                }
                val commands = buildList {
                    for (commandIndex in 0 until commandArray.length()) {
                        val command = commandArray.getJSONObject(commandIndex)
                        val name = command.getString("name")
                        val carrier = command.getInt("carrierHz")
                        val timingArray = command.getJSONArray("timingsMicros")
                        require(timingArray.length() in 3..511 && timingArray.length() % 2 == 1) {
                            "Saved IR timings are invalid."
                        }
                        val timings = (0 until timingArray.length()).map { timingArray.getInt(it) }
                        add(NamedInfraredCommand(
                            name,
                            InfraredCommand.parse(carrier.toString(), timings.joinToString(","), null)
                        ))
                    }
                }
                add(SavedInfraredProfile(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    brand = item.getString("brand"),
                    model = item.getString("model"),
                    category = ApplianceCategory.valueOf(item.getString("category")),
                    savedAtMillis = item.getLong("savedAtMillis"),
                    commands = commands
                ).also(::validate))
            }
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) {
            "Saved IR profiles contain duplicate IDs."
        }
        return profiles
    }

    fun newProfile(
        name: String,
        brand: String,
        model: String,
        category: ApplianceCategory,
        commands: List<NamedInfraredCommand>,
        nowMillis: Long = System.currentTimeMillis()
    ): SavedInfraredProfile = SavedInfraredProfile(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        brand = brand.trim(),
        model = model.trim(),
        category = category,
        savedAtMillis = nowMillis,
        commands = commands.toList()
    ).also(::validate)

    private fun validate(profile: SavedInfraredProfile) {
        require(profile.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,79}"))) { "Invalid IR profile ID." }
        require(profile.name.isNotBlank() && profile.name.length <= 80) { "Remote name must contain 1–80 characters." }
        require(profile.brand.isNotBlank() && profile.brand.length <= 80) { "Brand must contain 1–80 characters." }
        require(profile.model.isNotBlank() && profile.model.length <= 100) { "Model must contain 1–100 characters." }
        require(profile.savedAtMillis > 0) { "Invalid profile timestamp." }
        require(profile.commands.size in 1..MAX_COMMANDS_PER_PROFILE) { "An IR profile must contain 1–128 commands." }
        require(profile.commands.map { it.name.lowercase() }.distinct().size == profile.commands.size) {
            "IR profile command names must be unique."
        }
        profile.commands.forEach { command ->
            require(command.name.isNotBlank() && command.name.length <= 80 && command.name.none(Char::isISOControl)) {
                "Invalid IR command name."
            }
            InfraredCommand.parse(
                command.command.carrierHz.toString(),
                command.command.timingsMicros.joinToString(","),
                null
            )
        }
    }
}

object InfraredProfileStore {
    private const val PREFS = "openclone_universal_remote"
    private const val KEY_PROFILES = "saved_ir_profiles"

    fun load(context: Context): List<SavedInfraredProfile> = runCatching {
        InfraredProfileCodec.decode(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROFILES, "[]") ?: "[]"
        )
    }.getOrDefault(emptyList())

    fun save(context: Context, profile: SavedInfraredProfile): List<SavedInfraredProfile> {
        val current = load(context).filterNot { it.id == profile.id }
        val updated = (current + profile).takeLast(InfraredProfileCodec.MAX_PROFILES)
        val encoded = InfraredProfileCodec.encode(updated)
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, encoded).commit()) {
            "Could not save the IR profile on this device."
        }
        return updated
    }

    fun delete(context: Context, profileId: String): List<SavedInfraredProfile> {
        val updated = load(context).filterNot { it.id == profileId }
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, InfraredProfileCodec.encode(updated)).commit()) {
            "Could not delete the saved IR profile."
        }
        return updated
    }
}
