package org.openclone.app

data class NamedInfraredCommand(val name: String, val command: InfraredCommand)

/** Imports the raw-signal subset of Flipper-IRDB .ir files without executing file content. */
internal object FlipperIrFileParser {
    fun parse(source: String): List<NamedInfraredCommand> {
        require(source.length <= 64 * 1024) { "IR file exceeds the 64 KB import limit." }
        val lines = source.lineSequence().map(String::trim).toList()
        require(lines.any { it.equals("Filetype: IR signals file", ignoreCase = true) }) {
            "This is not a Flipper IR signals file."
        }
        require(lines.any { it.equals("Version: 1", ignoreCase = true) }) {
            "Unsupported Flipper IR file version."
        }
        val sections = mutableListOf<MutableList<String>>()
        lines.forEach { line ->
            if (line.startsWith("name:", ignoreCase = true)) sections += mutableListOf(line)
            else if (sections.isNotEmpty()) sections.last() += line
        }
        val commands = sections.mapNotNull { section ->
            val fields = section.filter { it.isNotBlank() && !it.startsWith("#") }
            fun value(key: String): String? = fields.firstOrNull { it.startsWith("$key:", true) }
                ?.substringAfter(':')?.trim()
            val name = value("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            require(name.length <= 80 && name.none(Char::isISOControl)) { "IR command name is invalid." }
            require(value("type")?.equals("raw", ignoreCase = true) == true) {
                "The imported file contains a non-raw command '$name'; only raw timing commands are supported currently."
            }
            val frequency = value("frequency")?.toIntOrNull()
                ?: throw IllegalArgumentException("IR command '$name' has no valid carrier frequency.")
            val data = value("data")
                ?: throw IllegalArgumentException("IR command '$name' has no raw timing data.")
            val timings = data.split(Regex("\\s+")).filter(String::isNotBlank).map {
                it.toIntOrNull() ?: throw IllegalArgumentException("IR command '$name' contains invalid timing data.")
            }.toMutableList()
            // Flipper raw captures may end in a space interval; Android's
            // ConsumerIrManager pattern ends on a mark, so discard that tail.
            if (timings.size % 2 == 0) timings.removeAt(timings.lastIndex)
            val command = InfraredCommand.parse(frequency.toString(), timings.joinToString(","), null)
            NamedInfraredCommand(name, command)
        }
        require(commands.isNotEmpty()) { "No supported raw IR commands were found in this file." }
        require(commands.size <= 128) { "IR file contains more than 128 commands." }
        require(commands.map { it.name.lowercase() }.distinct().size == commands.size) {
            "IR file contains duplicate command names."
        }
        return commands
    }
}
