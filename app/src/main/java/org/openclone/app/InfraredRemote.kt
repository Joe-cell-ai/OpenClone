package org.openclone.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.ConsumerIrManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

/** Validated raw IR command. Android's ConsumerIr API transmits but cannot learn/receive IR codes. */
data class InfraredCommand(val carrierHz: Int, val timingsMicros: IntArray) {
    companion object {
        fun parse(carrierHz: String, timings: String, supportedRanges: Array<out android.hardware.ConsumerIrManager.CarrierFrequencyRange>?): InfraredCommand {
            val carrier = carrierHz.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Enter a carrier frequency in Hz.")
            require(carrier in 20_000..60_000) { "Carrier frequency must be between 20,000 and 60,000 Hz." }
            if (supportedRanges != null && supportedRanges.isNotEmpty()) {
                require(supportedRanges.any { carrier in it.minFrequency..it.maxFrequency }) {
                    "This device does not support the selected carrier frequency."
                }
            }
            val values = timings.split(',', ' ', '\n', '\t')
                .filter { it.isNotBlank() }
                .map { it.toIntOrNull() ?: throw IllegalArgumentException("IR timings must be whole numbers in microseconds.") }
            require(values.size in 3..511 && values.size % 2 == 1) {
                "Enter 3–511 alternating mark/space timings; the list must end with a mark."
            }
            require(values.all { it in 1..100_000 }) { "Each IR timing must be between 1 and 100,000 microseconds." }
            require(values.sumOf { it.toLong() } <= 2_000_000L) { "The IR command is too long (maximum 2 seconds)." }
            return InfraredCommand(carrier, values.toIntArray())
        }
    }
}

object InfraredRemote {
    fun isAvailable(context: Context): Boolean =
        (context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true

    fun supportedCarrierRanges(context: Context): Array<ConsumerIrManager.CarrierFrequencyRange> {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: throw IllegalStateException("Android IR service is unavailable on this device.")
        check(manager.hasIrEmitter()) { "This phone does not report a built-in IR blaster." }
        return manager.carrierFrequencies ?: emptyArray()
    }

    fun transmit(context: Context, command: InfraredCommand) {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: throw IllegalStateException("Android IR service is unavailable on this device.")
        check(manager.hasIrEmitter()) { "This phone does not report a built-in IR blaster." }
        val ranges = manager.carrierFrequencies
        if (!ranges.isNullOrEmpty()) {
            require(ranges.any { command.carrierHz in it.minFrequency..it.maxFrequency }) {
                "The selected carrier frequency is unsupported by this phone."
            }
        }
        manager.transmit(command.carrierHz, command.timingsMicros)
    }
}

@Composable
fun InfraredApplianceRemoteCard() {
    val context = LocalContext.current
    val available = remember(context) { InfraredRemote.isAvailable(context) }
    var appliance by remember { mutableStateOf("TV") }
    var carrier by remember { mutableStateOf("38000") }
    var timings by remember { mutableStateOf("") }
    var prontoHex by remember { mutableStateOf("") }
    var importedCommands by remember { mutableStateOf(emptyList<NamedInfraredCommand>()) }
    var message by remember {
        mutableStateOf(
            if (available) "IR blaster detected. Enter a command's raw timing sequence to transmit it."
            else "No built-in IR blaster detected. Use a compatible external IR accessory or another IR-equipped phone."
        )
    }
    val importRemoteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected IR file.")
                val bytes = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64 * 1024) { "IR file exceeds the 64 KB import limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                FlipperIrFileParser.parse(bytes.toString(Charsets.UTF_8))
            }.onSuccess { commands ->
                importedCommands = commands
                message = "Imported ${commands.size} raw commands. Test each button with the appliance in sight."
            }.onFailure { error ->
                importedCommands = emptyList()
                message = error.message ?: "Could not import IR remote file."
            }
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📺 Appliance Remote · Infrared", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UberGuidoZ/Flipper-IRDB")))
            }) {
                Text("Browse community IR profiles")
            }
            Button(onClick = { importRemoteLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                Text("Import Flipper .ir remote file")
            }
            if (importedCommands.isNotEmpty()) {
                Text("Imported remote buttons", style = MaterialTheme.typography.titleSmall)
                importedCommands.forEach { item ->
                    Button(
                        enabled = available,
                        onClick = {
                            runCatching {
                                InfraredRemote.transmit(context, item.command)
                                message = "${item.name} command transmitted. Check whether the appliance responded."
                            }.onFailure { message = it.message ?: "Could not transmit imported command." }
                        }
                    ) { Text(item.name.replace('_', ' ')) }
                }
            }
            Text(
                "Control compatible TVs, air conditioners and set-top boxes using this phone's IR blaster. Select an appliance category, then enter a code timing sequence supplied for that exact model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (available) "Device capability: IR transmitter available" else "Device capability: no built-in IR transmitter",
                style = MaterialTheme.typography.labelMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("TV", "AC", "Set-top box", "Other").forEach { item ->
                    Button(onClick = { appliance = item }) { Text(if (appliance == item) "✓ $item" else item) }
                }
            }
            OutlinedTextField(
                value = carrier,
                onValueChange = { carrier = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Carrier frequency (Hz)") }
            )
            OutlinedTextField(
                value = timings,
                onValueChange = { timings = it.take(6000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Raw timings in microseconds (comma-separated)") },
                placeholder = { Text("9000,4500,560,560,560,1690, …") }
            )
            Button(
                enabled = available && timings.isNotBlank(),
                onClick = {
                    runCatching {
                        val command = InfraredCommand.parse(carrier, timings, InfraredRemote.supportedCarrierRanges(context))
                        InfraredRemote.transmit(context, command)
                        message = "$appliance IR command transmitted. Check whether the appliance responded."
                    }.onFailure { message = it.message ?: "Could not transmit the IR command." }
                }
            ) { Text("Transmit IR command") }
            OutlinedTextField(
                value = prontoHex,
                onValueChange = { prontoHex = it.take(6000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Pronto Hex code (optional)") },
                placeholder = { Text("0000 006D 0022 0000 …") }
            )
            Button(
                enabled = available && prontoHex.isNotBlank(),
                onClick = {
                    runCatching {
                        val command = ProntoHexDecoder.decode(prontoHex)
                        val supported = InfraredRemote.supportedCarrierRanges(context)
                        if (supported.isNotEmpty()) {
                            require(supported.any { command.carrierHz in it.minFrequency..it.maxFrequency }) {
                                "This phone does not support the Pronto Hex carrier frequency."
                            }
                        }
                        InfraredRemote.transmit(context, command)
                        message = "$appliance Pronto Hex command transmitted. Check whether the appliance responded."
                    }.onFailure { message = it.message ?: "Could not transmit Pronto Hex command." }
                }
            ) { Text("Transmit Pronto Hex") }
            Text(
                "Important: Android phones can transmit IR but generally cannot learn/receive remote signals through ConsumerIrManager. A model-specific code library and device testing are still needed for a ready-made brand/model remote.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
