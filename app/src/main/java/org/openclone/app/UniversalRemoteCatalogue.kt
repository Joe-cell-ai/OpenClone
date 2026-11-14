package org.openclone.app

/**
 * Protocol-neutral model for the Universal Remote Centre.
 *
 * A device may expose more than one transport (for example, IR and Wi-Fi).
 * The presence of a transport in a profile means that a real command mapping
 * must exist before the UI enables that action; it is not a compatibility claim.
 */
enum class ApplianceCategory(val displayName: String) {
    TELEVISION("TV"),
    AIR_CONDITIONER("Air conditioner"),
    SET_TOP_BOX("Set-top box"),
    FAN("Fan"),
    REFRIGERATOR("Refrigerator"),
    WASHING_MACHINE("Washing machine"),
    PROJECTOR("Projector"),
    AUDIO("Audio system"),
    LIGHTING("Lights"),
    SMART_PLUG("Smart plug"),
    CURTAIN("Curtains"),
    AIR_PURIFIER("Air purifier"),
    OTHER("Other")
}

enum class RemoteTransport(val displayName: String) {
    INFRARED("Infrared"),
    WIFI_LAN("Wi-Fi / local network"),
    BLUETOOTH_LE("Bluetooth LE"),
    MATTER("Matter"),
    RF_315_433("External RF hub"),
    MANUFACTURER_CLOUD("Manufacturer cloud")
}

enum class RemoteAction(val displayName: String) {
    POWER("Power"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    MUTE("Mute"),
    CHANNEL_UP("Channel up"),
    CHANNEL_DOWN("Channel down"),
    INPUT("Input"),
    HOME("Home"),
    BACK("Back"),
    MENU("Menu"),
    TEMPERATURE_UP("Temperature +"),
    TEMPERATURE_DOWN("Temperature −"),
    FAN_SPEED("Fan speed"),
    MODE("Mode"),
    SWING("Swing"),
    TIMER("Timer"),
    SPEED_UP("Speed +"),
    SPEED_DOWN("Speed −"),
    LIGHT_ON("Light on"),
    LIGHT_OFF("Light off"),
    START("Start"),
    STOP("Stop"),
    PAUSE("Pause"),
    CUSTOM("Custom")
}

data class RemoteCommandMapping(
    val action: RemoteAction,
    val transport: RemoteTransport,
    /** Protocol-specific command key, never an arbitrary URL or shell command. */
    val commandKey: String
) {
    init {
        require(commandKey.isNotBlank() && commandKey.length <= 160) {
            "A command mapping must have a 1–160 character command key."
        }
        require(commandKey.none { it.isISOControl() }) { "Command keys cannot contain control characters." }
    }
}

data class RemoteDeviceProfile(
    val id: String,
    val brand: String,
    val model: String,
    val category: ApplianceCategory,
    val transports: Set<RemoteTransport>,
    val commands: List<RemoteCommandMapping> = emptyList()
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}"))) { "Invalid device profile ID." }
        require(brand.isNotBlank() && brand.length <= 80) { "Brand must contain 1–80 characters." }
        require(model.isNotBlank() && model.length <= 100) { "Model must contain 1–100 characters." }
        require(transports.isNotEmpty()) { "A device profile must declare at least one transport." }
        require(commands.size <= 256) { "A profile cannot contain more than 256 command mappings." }
        require(commands.all { it.transport in transports }) {
            "Every command mapping must use a transport declared by this device profile."
        }
        require(commands.map { it.action to it.transport }.distinct().size == commands.size) {
            "A profile cannot map the same action and transport more than once."
        }
    }

    fun actionsFor(transport: RemoteTransport): Set<RemoteAction> =
        commands.asSequence().filter { it.transport == transport }.map { it.action }.toSet()

    fun mappingFor(action: RemoteAction, preferred: List<RemoteTransport>): RemoteCommandMapping? =
        preferred.firstNotNullOfOrNull { transport ->
            commands.firstOrNull { it.action == action && it.transport == transport }
        }
}

/** Categories are a discovery taxonomy, not a promise that every appliance is remotely controllable. */
object UniversalRemoteCatalogue {
    val categories: List<ApplianceCategory> = ApplianceCategory.entries.toList()

    val transportOptions: List<RemoteTransport> = RemoteTransport.entries.toList()

    fun categoriesFor(transport: RemoteTransport): Set<ApplianceCategory> = when (transport) {
        RemoteTransport.INFRARED -> setOf(
            ApplianceCategory.TELEVISION, ApplianceCategory.AIR_CONDITIONER,
            ApplianceCategory.SET_TOP_BOX, ApplianceCategory.FAN,
            ApplianceCategory.PROJECTOR, ApplianceCategory.AUDIO, ApplianceCategory.OTHER
        )
        RemoteTransport.WIFI_LAN, RemoteTransport.MATTER, RemoteTransport.MANUFACTURER_CLOUD ->
            categories.toSet()
        RemoteTransport.BLUETOOTH_LE ->
            setOf(ApplianceCategory.AUDIO, ApplianceCategory.LIGHTING, ApplianceCategory.FAN, ApplianceCategory.OTHER)
        RemoteTransport.RF_315_433 ->
            setOf(ApplianceCategory.FAN, ApplianceCategory.LIGHTING, ApplianceCategory.SMART_PLUG, ApplianceCategory.CURTAIN, ApplianceCategory.OTHER)
    }

    fun canAttempt(category: ApplianceCategory, transport: RemoteTransport): Boolean =
        category in categoriesFor(transport)
}

/** Common boundary for transport implementations; each adapter must enforce its own pairing and safety rules. */
interface UniversalRemoteTransportAdapter {
    val transport: RemoteTransport
    suspend fun isAvailable(): Boolean
    suspend fun send(profile: RemoteDeviceProfile, mapping: RemoteCommandMapping)
}

/** A scene is a user-authored sequence of known device actions, not executable code. */
data class RemoteSceneStep(
    val profileId: String,
    val action: RemoteAction
) {
    init {
        require(profileId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}"))) { "Invalid scene device profile ID." }
    }
}

data class RemoteScene(
    val id: String,
    val name: String,
    val steps: List<RemoteSceneStep>
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}"))) { "Invalid scene ID." }
        require(name.isNotBlank() && name.length <= 60) { "Scene name must contain 1–60 characters." }
        require(steps.isNotEmpty() && steps.size <= 20) { "A scene must contain 1–20 steps." }
    }
}
