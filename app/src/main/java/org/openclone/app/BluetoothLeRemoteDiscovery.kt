package org.openclone.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat

data class DiscoveredBleRemote(
    val id: String,
    val name: String,
    val rssi: Int,
    val advertisedServices: List<String>
)

object BleDiscoveryPermissions {
    fun requiredForSdk(sdkInt: Int): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        requiredForSdk(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

/** BLE advertisement discovery only. It never connects or writes to GATT characteristics. */
class BluetoothLeRemoteDiscovery(context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private var callback: ScanCallback? = null

    fun start(onDevice: (DiscoveredBleRemote) -> Unit, onError: (String) -> Unit) {
        check(callback == null) { "Bluetooth scan is already running." }
        val bluetooth = adapter ?: error("This device does not provide Bluetooth LE.")
        if (!bluetooth.isEnabled) error("Turn on Bluetooth to scan for nearby devices.")
        val bleScanner = scanner ?: error("Bluetooth LE scanning is unavailable.")
        val active = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = publish(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::publish)
            override fun onScanFailed(errorCode: Int) {
                callback = null
                onError("Bluetooth scan failed (Android error $errorCode).")
            }
            private fun publish(result: ScanResult) {
                val record = result.scanRecord
                val label = record?.deviceName?.takeIf { it.isNotBlank() } ?: "Unnamed BLE device"
                val uuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }.take(16)
                onDevice(
                    DiscoveredBleRemote(
                        id = result.device.address,
                        name = label.take(100),
                        rssi = result.rssi,
                        advertisedServices = uuids
                    )
                )
            }
        }
        callback = active
        try {
            bleScanner.startScan(active)
        } catch (error: SecurityException) {
            callback = null
            throw error
        } catch (error: Exception) {
            callback = null
            throw error
        }
    }

    fun stop() {
        val active = callback ?: return
        callback = null
        try {
            scanner?.stopScan(active)
        } catch (_: SecurityException) {
            // Permission may have been revoked while scanning.
        }
    }
}

@Composable
fun UniversalRemoteBleDiscoveryCard() {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<DiscoveredBleRemote>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Find nearby BLE advertisements. Discovery does not pair with or control appliances.") }
    val discovery = remember(context) { runCatching { BluetoothLeRemoteDiscovery(context) }.getOrNull() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it } && result.isNotEmpty()) {
            startBleScan(discovery, devices, { scanning = it }, { status = it })
        } else {
            status = "Bluetooth scan permission was not granted."
        }
    }

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
            Text("Bluetooth LE discovery", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scans nearby BLE advertisements using Android runtime permissions. It does not pair, connect, write characteristics or claim appliance control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = discovery != null && !scanning,
                    onClick = {
                        devices.clear()
                        if (BleDiscoveryPermissions.granted(context)) {
                            startBleScan(discovery, devices, { scanning = it }, { status = it })
                        } else {
                            permissionLauncher.launch(BleDiscoveryPermissions.requiredForSdk(Build.VERSION.SDK_INT))
                        }
                    }
                ) { Text("Scan BLE devices") }
                Button(
                    enabled = scanning,
                    onClick = {
                        discovery?.stop()
                        scanning = false
                        status = "Bluetooth scan stopped."
                    }
                ) { Text("Stop") }
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (devices.isEmpty()) {
                Text(if (scanning) "Waiting for BLE advertisements…" else "No BLE devices discovered yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                devices.sortedByDescending { it.rssi }.forEach { device ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text("Signal ${device.rssi} dBm · ${device.advertisedServices.size} advertised service UUID(s)", style = MaterialTheme.typography.bodySmall)
                        Text("Advertisement only — no device profile or control adapter selected.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

private fun startBleScan(
    discovery: BluetoothLeRemoteDiscovery?,
    devices: MutableList<DiscoveredBleRemote>,
    setScanning: (Boolean) -> Unit,
    setStatus: (String) -> Unit
) {
    if (discovery == null) {
        setStatus("Bluetooth LE is unavailable on this device.")
        return
    }
    runCatching {
        setScanning(true)
        setStatus("Scanning for nearby Bluetooth LE advertisements…")
        discovery.start(
            onDevice = { found ->
                val index = devices.indexOfFirst { it.id == found.id }
                if (index >= 0) devices[index] = found
                else if (devices.size < 64) devices.add(found)
            },
            onError = {
                setScanning(false)
                setStatus(it)
            }
        )
    }.onFailure {
        setScanning(false)
        setStatus(it.message ?: "Could not start Bluetooth LE scanning.")
    }
}
