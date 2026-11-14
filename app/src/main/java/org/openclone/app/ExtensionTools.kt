package org.openclone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openclone.app.data.ExtensionDiagnosticsLog
import org.openclone.app.data.PrivacyAuditRepository
import org.openclone.app.data.StorageSnapshot
import org.openclone.app.data.InstalledAppRepository
import org.openclone.app.data.InstalledCloneRepository
import org.openclone.app.data.InstalledClone
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.DataInputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

private const val PREFS = "openclone_extension_tools"
private const val PROFILES = "proxy_profiles"
private const val AUTOMATION = "daily_clone_update_check"
private const val APP_LOCK = "app_lock_enabled"
private const val WORK_NAME = "openclone_daily_clone_update_check"
internal const val UPDATE_CHANNEL = "openclone_clone_updates"

data class ProxyProfile(val name: String, val host: String, val port: Int, val protocol: String = "HTTP_CONNECT") {
    companion object {
        fun validate(name: String, host: String, port: Int, protocol: String = "HTTP_CONNECT") {
            require(name.trim().isNotEmpty() && name.length <= 40) { "Profile name must contain 1–40 characters." }
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "Enter a valid proxy host." }
            require(port in 1..65535) { "Port must be between 1 and 65535." }
            require(protocol in setOf("HTTP_CONNECT", "SOCKS5")) { "Unsupported proxy protocol." }
        }
    }
}

class ProxyProfileRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized fun getAll(): List<ProxyProfile> = runCatching {
        val array = JSONArray(prefs.getString(PROFILES, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val profile = ProxyProfile(item.optString("name"), item.optString("host"), item.optInt("port"), item.optString("protocol", "HTTP_CONNECT"))
                runCatching { ProxyProfile.validate(profile.name, profile.host, profile.port, profile.protocol) }.onSuccess { add(profile) }
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun save(profile: ProxyProfile) {
        ProxyProfile.validate(profile.name, profile.host, profile.port)
        val updated = (getAll().filterNot { it.name.equals(profile.name, true) } + profile).sortedBy { it.name.lowercase() }
        require(updated.size <= 10) { "A maximum of 10 proxy profiles can be saved." }
        val array = JSONArray()
        updated.forEach { p -> array.put(JSONObject().put("name", p.name.trim()).put("host", p.host.trim()).put("port", p.port)) }
        check(prefs.edit().putString(PROFILES, array.toString()).commit()) { "Could not save proxy profile." }
    }

    @Synchronized fun delete(name: String) {
        val array = JSONArray()
        getAll().filterNot { it.name == name }.forEach { p -> array.put(JSONObject().put("name", p.name).put("host", p.host).put("port", p.port)) }
        check(prefs.edit().putString(PROFILES, array.toString()).commit()) { "Could not remove proxy profile." }
    }
}

object ProxyConnectivityTester {
    /** Performs a real unauthenticated HTTP CONNECT or SOCKS5 handshake to example.com:443. */
    fun test(profile: ProxyProfile, timeoutMillis: Int = 4000) {
        ProxyProfile.validate(profile.name, profile.host, profile.port, profile.protocol)
        java.net.Socket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(profile.host.trim(), profile.port), timeoutMillis)
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            when (profile.protocol) {
                "HTTP_CONNECT" -> {
                    output.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val response = BufferedReader(InputStreamReader(input, Charsets.US_ASCII)).readLine().orEmpty()
                    require(response.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) {
                        "HTTP proxy rejected CONNECT: " + response.take(120)
                    }
                }
                "SOCKS5" -> {
                    output.write(byteArrayOf(5, 1, 0))
                    output.flush()
                    val greeting = ByteArray(2)
                    input.readFully(greeting)
                    require(greeting[0].toInt() == 5 && greeting[1].toInt() == 0) {
                        "SOCKS5 proxy requires authentication or rejected no-auth mode."
                    }
                    val host = "example.com".toByteArray(Charsets.US_ASCII)
                    output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(1, 0xBB.toByte()))
                    output.flush()
                    val header = ByteArray(4)
                    input.readFully(header)
                    require(header[0].toInt() == 5 && header[1].toInt() == 0) {
                        "SOCKS5 CONNECT request was rejected (code " + (header[1].toInt() and 0xff) + ")."
                    }
                    when (header[3].toInt() and 0xff) {
                        1 -> input.skipBytes(4)
                        3 -> input.skipBytes(input.readUnsignedByte())
                        4 -> input.skipBytes(16)
                        else -> error("SOCKS5 returned an unsupported address type.")
                    }
                    input.skipBytes(2)
                }
            }
        }
    }
}

data class NetworkSnapshot(val connected: Boolean, val transport: String, val validated: Boolean, val metered: Boolean, val vpn: Boolean) {
    companion object {
        fun read(context: Context): NetworkSnapshot {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
                ?: return NetworkSnapshot(false, "Offline", false, cm.isActiveNetworkMetered, false)
            val transports = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Mobile")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
            }
            return NetworkSnapshot(
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transports.joinToString(" + ").ifBlank { "Other" },
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                cm.isActiveNetworkMetered,
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            )
        }
    }
}

object OpenCloneNotificationChannels {
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL, "Clone update alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when an original app used by a clone has an update available."
            })
        }
    }
    fun openSettings(context: Context) {
        ensure(context)
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object CloneUpdateAutomation {
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTOMATION, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTOMATION, enabled).apply()
        if (enabled) {
            OpenCloneNotificationChannels.ensure(context)
            val request = PeriodicWorkRequestBuilder<CloneUpdateCheckWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class CloneUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val pm = applicationContext.packageManager
            val apps = org.openclone.app.data.InstalledAppRepository(pm).getInstalledApps(true)
            val count = org.openclone.app.data.InstalledCloneRepository(pm).findClones(apps).count { it.updateAvailable }
            if (count > 0) {
                OpenCloneNotificationChannels.ensure(applicationContext)
                val allowed = Build.VERSION.SDK_INT < 33 ||
                    applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (allowed) {
                    val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, UPDATE_CHANNEL)
                        else Notification.Builder(applicationContext)
                    manager.notify(4201, builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("OpenClone updates available")
                        .setContentText(count.toString() + " cloned app(s) have an update available for their original app.")
                        .setAutoCancel(true).build())
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

@Composable
fun ExtensionToolsScreen(onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit, onOpenApps: () -> Unit, onOpenClones: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ProxyProfileRepository(context) }
    var profiles by remember { mutableStateOf(repository.getAll()) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    var protocol by remember { mutableStateOf("HTTP_CONNECT") }
    var privacyAudits by remember { mutableStateOf(PrivacyAuditRepository(context).getAll()) }
    var storage by remember { mutableStateOf(StorageSnapshot.read(context)) }
    var installedClones by remember { mutableStateOf<List<InstalledClone>>(emptyList()) }
    var showClearCache by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(ExtensionDiagnosticsLog(context).getAll()) }
    var message by remember { mutableStateOf("Saved profiles are not applied to cloned apps or device traffic.") }
    var network by remember { mutableStateOf(NetworkSnapshot.read(context)) }
    var automation by remember { mutableStateOf(CloneUpdateAutomation.isEnabled(context)) }
    var appLockEnabled by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(APP_LOCK, false)) }
    var appLockMessage by remember { mutableStateOf("Uses the Android device screen lock; OpenClone does not store a separate password.") }

    val refreshNetwork = { network = NetworkSnapshot.read(context) }
    LaunchedEffect(Unit) {
        OpenCloneNotificationChannels.ensure(context)
        refreshNetwork()
        withContext(Dispatchers.IO) {
            installedClones = runCatching {
                val apps = InstalledAppRepository(context.packageManager).getInstalledApps(true)
                InstalledCloneRepository(context.packageManager).findClones(apps)
            }.getOrDefault(emptyList())
            storage = StorageSnapshot.read(context)
        }
        privacyAudits = PrivacyAuditRepository(context).getAll()
        diagnostics = ExtensionDiagnosticsLog(context).getAll()
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refreshNetwork() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { refreshNetwork() }
            override fun onLost(network: Network) { refreshNetwork() }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Extensions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Built-in tools and supported Android integrations. Restricted capabilities are clearly marked instead of shown as working.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ExtensionNavigationCard("🎨", "Icon & Name Studio", "Clone label, package ID, custom PNG and badge settings", onOpenApps)
        ExtensionNavigationCard("🧩", "Clone Profiles", "Save, apply, rename, duplicate and delete clone configurations", onOpenProfiles)
        ExtensionNavigationCard("🗂️", "Multi-Clone Manager", "Search, sort, multi-select and system-confirmed uninstall", onOpenClones)
        ExtensionNavigationCard("🎭", "Theme Studio", "System, Light, Dark, AMOLED and four accent palettes", onOpenSettings)
        ExtensionNavigationCard("🛡️", "Privacy Guard", "Review requested permissions and remove selected manifest declarations", onOpenApps)
        ExtensionNavigationCard("💾", "Storage Manager", "Installed clone APK/split footprint; private app data is OS-restricted", onOpenClones)
        ExtensionNavigationCard("📦", "Backup & Restore", "Versioned clone-profile backup and restore", onOpenSettings)
        ExtensionNavigationCard("🧰", "Developer Toolkit", "APK inspection, compatibility preflight and redacted build-history export", onOpenSettings)


        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️ Privacy Guard · Audit history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { privacyAudits = PrivacyAuditRepository(context).getAll() }) { Text("Refresh") }
                }
                Text("Recorded clone-build permission decisions: ${privacyAudits.size}. These are manifest-removal choices, not runtime permission grants.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                privacyAudits.take(5).forEach { audit ->
                    Text("${audit.clonePackage} · ${audit.removedPermissions.size} of ${audit.requestedPermissions.size} removed · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(audit.recordedAtMillis))}", style = MaterialTheme.typography.labelSmall)
                }
                if (privacyAudits.isEmpty()) Text("No audit entries yet. A record is created when a clone build completes.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("💾 Storage Manager", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        scope.launch { storage = withContext(Dispatchers.IO) { StorageSnapshot.read(context) } }
                    }) { Text("Refresh") }
                }
                Text("OpenClone cache: ${formatBytes(storage.cacheBytes)}")
                Text("OpenClone files: ${formatBytes(storage.filesBytes)}")
                Text("Generated clone outputs: ${formatBytes(storage.buildOutputBytes)} · ${storage.buildOutputFiles} file(s)")
                Text("Installed clones: ${installedClones.size}. APK footprint is shown per clone; Android does not expose another app's private data/cache size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showClearCache = true }) { Text("Clear OpenClone cache") }
                    TextButton(onClick = { onOpenClones() }) { Text("View clone sizes") }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🧰 Developer Toolkit · Runtime diagnostics", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { diagnostics = ExtensionDiagnosticsLog(context).getAll() }) { Text("Refresh") }
                }
                Text("${diagnostics.size} bounded diagnostic event(s). Export contains event codes and redacted summaries, not signing keys or local file paths.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    val report = ExtensionDiagnosticsLog(context).exportJson()
                    val share = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, report)
                    context.startActivity(Intent.createChooser(share, "Share OpenClone diagnostics"))
                }) { Text("Share runtime diagnostics") }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌐 Proxy Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Save up to 10 HTTP CONNECT or SOCKS5 endpoints and test an unauthenticated tunnel handshake to example.com:443. Profiles do not route clone traffic or change Android's device-wide proxy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Profile name") })
                OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Proxy host") })
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { protocol = "HTTP_CONNECT" }) { Text((if (protocol == "HTTP_CONNECT") "✓ " else "") + "HTTP CONNECT") }
                    TextButton(onClick = { protocol = "SOCKS5" }) { Text((if (protocol == "SOCKS5") "✓ " else "") + "SOCKS5") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            repository.save(ProxyProfile(name, host, port.toInt(), protocol))
                            profiles = repository.getAll()
                            message = "Proxy profile saved."
                        }.onFailure { message = it.message ?: "Could not save profile." }
                    }) { Text("Save") }
                    TextButton(onClick = {
                        val targetPort = port.toIntOrNull()
                        if (host.isBlank() || targetPort == null || targetPort !in 1..65535) message = "Enter a valid host and port."
                        else {
                            message = "Testing TCP connection…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ProxyConnectivityTester.test(ProxyProfile(name.ifBlank { "Test" }, host, targetPort!!, protocol)) }
                                }
                                message = result.fold(
                                    { "Proxy handshake succeeded. Authentication and cloned-app routing are not configured." },
                                    { "Connection failed: " + (it.message ?: "host unreachable") }
                                )
                            }
                        }
                    }) { Text("Test host") }
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Medium)
                            Text(p.protocol + " · " + p.host + ":" + p.port, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { name = p.name; host = p.host; port = p.port.toString(); protocol = p.protocol }) { Text("Edit") }
                        TextButton(onClick = { repository.delete(p.name); profiles = repository.getAll() }) { Text("Delete") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("📡 Network Monitor", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { network = NetworkSnapshot.read(context) }) { Text("Refresh") }
                }
                Text("Connection: " + if (network.connected) "Internet capability" else "Offline")
                Text("Transport: " + network.transport)
                Text("Internet validated: " + if (network.validated) "Yes" else "No")
                Text("Metered: " + if (network.metered) "Yes" else "No")
                Text("VPN transport: " + if (network.vpn) "Active" else "Not detected")
                Text("Android connectivity diagnostics only; no traffic from other apps is captured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔔 Notification Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Manage OpenClone's own clone-update notification channel. Third-party clone notifications cannot be rewritten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { OpenCloneNotificationChannels.openSettings(context) }) { Text("Open notification settings") }
                if (installedClones.isNotEmpty()) {
                    Text("Per-clone Android notification settings", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    installedClones.forEach { clone ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(clone.cloneLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, clone.clonePackage)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Manage") }
                        }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚙️ Automation Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Check daily for source-app updates used by installed clones. OpenClone only reports updates; it never installs or updates apps automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (automation) "Daily check enabled" else "Daily check disabled", modifier = Modifier.weight(1f))
                    Switch(checked = automation, onCheckedChange = { enabled ->
                        runCatching { CloneUpdateAutomation.setEnabled(context, enabled); automation = enabled }
                            .onFailure { message = it.message ?: "Could not change schedule." }
                    })
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔒 App Lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Protect OpenClone with the Android device PIN, pattern or password. No custom password is stored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (appLockEnabled) "App lock enabled" else "App lock disabled", modifier = Modifier.weight(1f))
                    Switch(checked = appLockEnabled, onCheckedChange = { enabled ->
                        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (enabled && !keyguard.isDeviceSecure) {
                            appLockMessage = "Set a screen lock first. Android security settings have been opened."
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(APP_LOCK, enabled).apply()
                            appLockEnabled = enabled
                            (context as? Activity)?.recreate()
                        }
                    })
                }
                Text(appLockMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ExtensionStatusCard("🌍 WebView Manager", "OpenClone has no embedded WebView workflow today. A fail-closed HTTPS host policy is implemented for a future OpenClone-owned WebView; it cannot control cloned-app WebViews.")
        ExtensionStatusCard("🖥️ Remote Control", "HMAC request-authentication and time-window validation primitives are implemented and tested. No network listener or remote actions are enabled until secure pairing, replay protection and explicit local consent are complete.")
        ExtensionStatusCard("🔄 Extension Updater", "Signed descriptor and package SHA-256/ECDSA verification primitives are implemented and tested. Updates remain disabled until a trusted publisher public key and signed catalogue are provisioned.")
    }

    if (showClearCache) {
        AlertDialog(
            onDismissRequest = { showClearCache = false },
            title = { Text("Clear OpenClone cache?") },
            text = { Text("Only OpenClone temporary cache files will be removed. Clone APK outputs, profiles, signing identities and cloned-app data are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    context.cacheDir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
                    showClearCache = false
                    scope.launch { storage = withContext(Dispatchers.IO) { StorageSnapshot.read(context) } }
                }) { Text("Clear cache") }
            },
            dismissButton = { TextButton(onClick = { showClearCache = false }) { Text("Cancel") } }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} KB"
}

@Composable
private fun ExtensionNavigationCard(icon: String, title: String, description: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ExtensionStatusCard(icon: String, description: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppLockGate(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔒", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("OpenClone is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Confirm your Android device screen lock to continue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onUnlock) { Text("Unlock OpenClone") }
    }
}
