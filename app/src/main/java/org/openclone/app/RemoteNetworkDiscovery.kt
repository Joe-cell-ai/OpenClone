package org.openclone.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.InetAddress

/** Only service advertisements with known public service types are surfaced; discovery never implies control support. */
data class DiscoveredRemoteDevice(
    val id: String,
    val name: String,
    val serviceType: String,
    val transport: RemoteTransport,
    val host: String?,
    val port: Int
)

object RemoteServiceTypes {
    const val GOOGLE_CAST = "_googlecast._tcp."
    const val AIRPLAY = "_airplay._tcp."
    const val MATTER_OPERATIONAL = "_matter._tcp."
    const val MATTER_COMMISSIONABLE = "_matterc._udp."

    val supported = listOf(GOOGLE_CAST, AIRPLAY, MATTER_OPERATIONAL, MATTER_COMMISSIONABLE)

    fun transportFor(serviceType: String): RemoteTransport? = when (serviceType.lowercase()) {
        GOOGLE_CAST, AIRPLAY -> RemoteTransport.WIFI_LAN
        MATTER_OPERATIONAL, MATTER_COMMISSIONABLE -> RemoteTransport.MATTER
        else -> null
    }

    fun protocolLabel(serviceType: String): String = when (serviceType.lowercase()) {
        GOOGLE_CAST -> "Google Cast advertisement"
        AIRPLAY -> "AirPlay advertisement"
        MATTER_OPERATIONAL, MATTER_COMMISSIONABLE -> "Matter DNS-SD advertisement"
        else -> "Unknown service"
    }
}

/** Lifecycle-bound DNS-SD discovery. It resolves metadata only and never sends appliance commands. */
class RemoteNetworkDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: throw IllegalStateException("Android network service discovery is unavailable.")
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val seen = mutableSetOf<String>()
    private var stopped = false

    fun start(
        onDevice: (DiscoveredRemoteDevice) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        check(listeners.isEmpty()) { "Network discovery is already running." }
        stopped = false
        RemoteServiceTypes.supported.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    runCatching { manager.stopServiceDiscovery(this) }
                    listeners.remove(type)
                    onError("Could not scan $type (Android error $errorCode).")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    runCatching { manager.stopServiceDiscovery(this) }
                    listeners.remove(type)
                    onError("Could not stop $type scan (Android error $errorCode).")
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    listeners.remove(type)
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (stopped || RemoteServiceTypes.transportFor(serviceInfo.serviceType) == null) return
                    val key = "${serviceInfo.serviceType.lowercase()}|${serviceInfo.serviceName}"
                    if (!seen.add(key)) return
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            seen.remove(key)
                            if (!stopped) onError("Could not resolve ${info.serviceName} (Android error $errorCode).")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (stopped) return
                            onDevice(
                                DiscoveredRemoteDevice(
                                    id = key,
                                    name = info.serviceName.take(120),
                                    serviceType = info.serviceType,
                                    transport = RemoteServiceTypes.transportFor(info.serviceType)!!,
                                    host = info.host?.hostAddress,
                                    port = info.port
                                )
                            )
                        }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = "${serviceInfo.serviceType.lowercase()}|${serviceInfo.serviceName}"
                    seen.remove(key)
                    if (!stopped) onLost(key)
                }
            }
            listeners[type] = listener
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (error: Exception) {
                listeners.remove(type)
                onError(error.message ?: "Could not start local network discovery.")
            }
        }
    }

    fun stop() {
        stopped = true
        listeners.values.toList().forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        listeners.clear()
        seen.clear()
    }
}

@Composable
fun UniversalRemoteDiscoveryCard() {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<DiscoveredRemoteDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Scan your local network for compatible Cast, AirPlay and Matter advertisements.") }
    val discovery = remember(context) { runCatching { RemoteNetworkDiscovery(context) }.getOrNull() }

    DisposableEffect(discovery) {
        onDispose {
            discovery?.stop()
            scanning = false
        }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Universal Remote Centre", style = MaterialTheme.typography.titleMedium)
            Text(
                "Discover supported device advertisements on your local Wi-Fi. Discovery identifies endpoints only; a verified protocol adapter and device profile are still required before control is enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = discovery != null && !scanning,
                    onClick = {
                        devices.clear()
                        status = "Searching local network…"
                        scanning = true
                        runCatching {
                            discovery!!.start(
                                onDevice = { device ->
                                    if (devices.none { it.id == device.id } && devices.size < 64) devices.add(device)
                                },
                                onLost = { id -> devices.removeAll { it.id == id } },
                                onError = { error -> status = error }
                            )
                        }.onFailure {
                            scanning = false
                            status = it.message ?: "Could not start network discovery."
                        }
                    }
                ) { Text("Scan devices") }
                Button(
                    enabled = scanning,
                    onClick = {
                        discovery?.stop()
                        scanning = false
                        status = "Discovery stopped."
                    }
                ) { Text("Stop") }
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Waiting for local devices…" else "No discovered devices yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                devices.forEach { device ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${RemoteServiceTypes.protocolLabel(device.serviceType)} · ${device.host ?: "address unavailable"}:${device.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Discovered only — control adapter not yet available.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (discovery == null) {
                Text("Android local network discovery is unavailable on this device.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
