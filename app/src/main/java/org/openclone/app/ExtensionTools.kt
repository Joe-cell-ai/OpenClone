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
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import org.openclone.app.data.ManagedWebViewPolicy
import org.openclone.app.data.TrustedPublisher
import org.openclone.app.data.TrustedPublisherRepository
import org.openclone.app.data.CatalogueExtension
import org.openclone.app.data.ExtensionCatalogueClient
import org.openclone.app.data.ExtensionPackageDownloader
import org.openclone.app.data.ExtensionPackageInstaller
import org.openclone.app.data.ExtensionInstallStatusStore
import org.openclone.app.data.CloneProxyAssignmentRepository
import org.openclone.app.RemoteControlPairingStore
import org.openclone.app.RemotePairingInfo
import org.openclone.app.RemoteControlService
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
        ProxyProfile.validate(profile.name, profile.host, profile.port, profile.protocol)
        val updated = (getAll().filterNot { it.name.equals(profile.name, true) } + profile).sortedBy { it.name.lowercase() }
        require(updated.size <= 10) { "A maximum of 10 proxy profiles can be saved." }
        val array = JSONArray()
        updated.forEach { p -> array.put(JSONObject().put("name", p.name.trim()).put("host", p.host.trim()).put("port", p.port).put("protocol", p.protocol)) }
        check(prefs.edit().putString(PROFILES, array.toString()).commit()) { "Could not save proxy profile." }
    }

    @Synchronized fun replaceAll(profiles: List<ProxyProfile>) {
        require(profiles.size <= 10) { "A maximum of 10 proxy profiles can be restored." }
        profiles.forEach { ProxyProfile.validate(it.name, it.host, it.port, it.protocol) }
        require(profiles.map { it.name.lowercase() }.distinct().size == profiles.size) { "Proxy profile names must be unique." }
        val array = JSONArray()
        profiles.sortedBy { it.name.lowercase() }.forEach { profile ->
            array.put(JSONObject().put("name", profile.name.trim()).put("host", profile.host.trim())
                .put("port", profile.port).put("protocol", profile.protocol))
        }
        check(prefs.edit().putString(PROFILES, array.toString()).commit()) { "Could not restore proxy profiles." }
    }

    @Synchronized fun delete(name: String) {
        val array = JSONArray()
        getAll().filterNot { it.name == name }.forEach { p -> array.put(JSONObject().put("name", p.name).put("host", p.host).put("port", p.port).put("protocol", p.protocol)) }
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
    private const val INTERVAL_HOURS = "update_check_interval_hours"
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTOMATION, false)
    fun intervalHours(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(INTERVAL_HOURS, 24L).coerceIn(24L, 168L)

    fun setIntervalHours(context: Context, hours: Long) {
        require(hours in setOf(24L, 72L, 168L)) { "Choose a 1-day, 3-day or 7-day interval." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(INTERVAL_HOURS, hours).apply()
        if (isEnabled(context)) schedule(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTOMATION, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun schedule(context: Context) {
        OpenCloneNotificationChannels.ensure(context)
        val request = PeriodicWorkRequestBuilder<CloneUpdateCheckWorker>(intervalHours(context), TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
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
    var storage by remember { mutableStateOf(StorageSnapshot(0L, 0L, 0L, 0)) }
    var installedClones by remember { mutableStateOf<List<InstalledClone>>(emptyList()) }
    var showClearCache by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(ExtensionDiagnosticsLog(context).getAll()) }
    var webViewHosts by remember {
        mutableStateOf(context.getSharedPreferences("openclone_managed_webview", Context.MODE_PRIVATE)
            .getStringSet("allowed_hosts", emptySet()).orEmpty().toList().sorted())
    }
    var webViewHostInput by remember { mutableStateOf("") }
    var webViewMessage by remember { mutableStateOf("Add exact HTTPS hosts before opening the isolated browser.") }
    var message by remember { mutableStateOf("Saved profiles can be assigned to a selected clone in Per-clone Routing.") }
    val publisherRepository = remember { TrustedPublisherRepository(context) }
    var trustedPublishers by remember { mutableStateOf(publisherRepository.all()) }
    var publisherLabel by remember { mutableStateOf("OpenClone Catalogue Publisher") }
    var publisherKey by remember { mutableStateOf("-----BEGIN PUBLIC KEY-----\\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFy/nyuFgixka7Vj+cH7GhqYIla3r\\naZUAeujcQU72BYVXhgjilnwKGAaT/rSau4SrHvvhO9/ql9tMQrnRRKyKHQ==\\n-----END PUBLIC KEY-----") }
    var publisherFingerprintConfirmation by remember { mutableStateOf("") }
    var publisherCandidate by remember { mutableStateOf<TrustedPublisher?>(null) }
    var updaterMessage by remember { mutableStateOf("Import a publisher EC public key, inspect its fingerprint, then confirm the fingerprint to trust it.") }
    var installStatus by remember { mutableStateOf(ExtensionInstallStatusStore.read(context)) }
    var catalogueUrl by remember { mutableStateOf("https://raw.githubusercontent.com/Joe-cell-ai/OpenClone/main/extension-catalogue/catalogue.json") }
    var catalogueEntries by remember { mutableStateOf<List<CatalogueExtension>>(emptyList()) }
    var updaterBusy by remember { mutableStateOf(false) }
    var remotePairingInfo by remember { mutableStateOf<RemotePairingInfo?>(null) }
    var remoteMessage by remember { mutableStateOf("Remote control is off. Start it only when you need a trusted local-network session.") }
    val assignmentRepository = remember { CloneProxyAssignmentRepository(context) }
    var proxyAssignments by remember { mutableStateOf(assignmentRepository.getAll()) }
    var selectedClonePackage by remember { mutableStateOf("") }
    var selectedProxyProfile by remember { mutableStateOf("") }
    var cloneMenuExpanded by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var pendingProxyIntent by remember { mutableStateOf<Intent?>(null) }
    var proxyRoutingMessage by remember { mutableStateOf("Assign a saved proxy profile to one clone. Android permits one active VPN per user/profile.") }
    val vpnConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingProxyIntent
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            runCatching {
                context.startForegroundService(pending)
                proxyRoutingMessage = "Android VPN consent granted. Starting the selected clone route…"
            }.onFailure { proxyRoutingMessage = it.message ?: "Could not start the clone VPN." }
        } else {
            proxyRoutingMessage = "VPN permission was not granted; no clone traffic was routed."
        }
        pendingProxyIntent = null
    }
    var network by remember { mutableStateOf(NetworkSnapshot.read(context)) }
    var automation by remember { mutableStateOf(CloneUpdateAutomation.isEnabled(context)) }
    var automationInterval by remember { mutableStateOf(CloneUpdateAutomation.intervalHours(context)) }
    var appLockEnabled by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(APP_LOCK, false)) }
    var appLockMessage by remember { mutableStateOf("Uses the Android device screen lock; OpenClone does not store a separate password.") }

    val refreshNetwork = { network = NetworkSnapshot.read(context) }
    LaunchedEffect(installedClones, profiles) {
        if (installedClones.none { it.clonePackage == selectedClonePackage }) {
            selectedClonePackage = installedClones.firstOrNull()?.clonePackage.orEmpty()
        }
        if (profiles.none { it.name == selectedProxyProfile }) {
            selectedProxyProfile = profiles.firstOrNull()?.name.orEmpty()
        }
    }
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

        ExtensionNavigationCard("🎨", "Icon & Name Studio", "Clone label, package ID, custom PNG, badge settings and source icon diagnostics", onOpenApps)
        ExtensionNavigationCard("🧩", "Clone Profiles", "Save, apply, rename, duplicate and delete clone configurations", onOpenProfiles)
        ExtensionNavigationCard("🗂️", "Multi-Clone Manager", "Group by original app, search, sort, multi-select and system-confirmed uninstall", onOpenClones)
        ExtensionNavigationCard("🎭", "Theme Studio", "System, Light, Dark, AMOLED, four presets and custom hex accent", onOpenSettings)
        ExtensionNavigationCard("🛡️", "Privacy Guard", "Review requested permissions, remove manifest declarations and view build audit history", onOpenApps)
        ExtensionNavigationCard("💾", "Storage Manager", "OpenClone cache cleanup, generated output totals and Android clone storage details", onOpenClones)
        ExtensionNavigationCard("📦", "Backup & Restore", "Clone profiles, appearance, proxy settings and automation cadence backup", onOpenSettings)
        ExtensionNavigationCard("🧰", "Developer Toolkit", "APK diagnostics, compatibility preflight and redacted runtime log export", onOpenSettings)


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
                installedClones.forEach { clone ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(clone.cloneLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${clone.clonePackage}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Android storage details") }
                    }
                }
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
                Text("📦 Extension Updater · Trusted publishers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Trust publisher EC public keys only after checking their SHA-256 fingerprint through a separate trusted channel. Catalogue and package verification APIs are available; a hosted catalogue is not configured by default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(publisherLabel, { publisherLabel = it.take(120) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Publisher name") })
                OutlinedTextField(publisherKey, { publisherKey = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text("EC public key (PEM or Base64)") })
                Button(onClick = {
                    runCatching { publisherCandidate = publisherRepository.inspect(publisherLabel, publisherKey); updaterMessage = "Fingerprint: " + publisherCandidate!!.fingerprintSha256 }
                        .onFailure { publisherCandidate = null; updaterMessage = it.message ?: "Could not inspect publisher key." }
                }) { Text("Inspect fingerprint") }
                publisherCandidate?.let { candidate ->
                    Text("Confirm this fingerprint using the publisher's independent official channel:", style = MaterialTheme.typography.bodySmall)
                    Text(candidate.fingerprintSha256.chunked(4).joinToString(" "), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(publisherFingerprintConfirmation, { publisherFingerprintConfirmation = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Type the full SHA-256 fingerprint") })
                    Button(onClick = {
                        runCatching {
                            publisherRepository.trust(candidate, publisherFingerprintConfirmation)
                            trustedPublishers = publisherRepository.all()
                            publisherCandidate = null
                            publisherFingerprintConfirmation = ""
                            publisherKey = ""
                            updaterMessage = "Publisher trusted. Its key can now verify signed catalogues."
                            ExtensionDiagnosticsLog(context).append("updater", "publisher_trusted", "A publisher key was explicitly trusted.")
                        }.onFailure { updaterMessage = it.message ?: "Could not trust publisher." }
                    }) { Text("Trust publisher") }
                }
                trustedPublishers.forEach { publisher ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(publisher.label, fontWeight = FontWeight.Medium)
                            Text(publisher.fingerprintSha256.chunked(4).joinToString(" "), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            publisherRepository.revoke(publisher.id)
                            trustedPublishers = publisherRepository.all()
                            catalogueEntries = emptyList()
                            updaterMessage = "Publisher trust revoked. Catalogues from this key are no longer trusted."
                            ExtensionDiagnosticsLog(context).append("updater", "publisher_revoked", "A publisher key was revoked.")
                        }) { Text("Revoke") }
                    }
                }
                OutlinedTextField(catalogueUrl, { catalogueUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Signed catalogue HTTPS URL") })
                Button(enabled = !updaterBusy && trustedPublishers.isNotEmpty(), onClick = {
                    updaterBusy = true
                    updaterMessage = "Downloading and verifying signed catalogue…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ExtensionCatalogueClient.fetchAndVerify(catalogueUrl, publisherRepository, currentApi = 1) }
                        }
                        updaterBusy = false
                        result.fold(
                            { entries ->
                                catalogueEntries = entries
                                updaterMessage = "Verified ${entries.size} compatible extension(s)."
                                ExtensionDiagnosticsLog(context).append("updater", "catalogue_verified", "A signed catalogue was verified.")
                            },
                            { error ->
                                catalogueEntries = emptyList()
                                updaterMessage = error.message ?: "Catalogue verification failed."
                                ExtensionDiagnosticsLog(context).append("updater", "catalogue_rejected", "A catalogue failed verification.")
                            }
                        )
                    }
                }) { Text(if (updaterBusy) "Please wait…" else "Fetch & verify catalogue") }
                catalogueEntries.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.id + " · " + entry.version, fontWeight = FontWeight.Medium)
                            Text(entry.publisher + " · API " + entry.minimumOpenCloneApi + "–" + entry.maximumOpenCloneApi, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(enabled = !updaterBusy, onClick = {
                            updaterBusy = true
                            updaterMessage = "Downloading and verifying " + entry.id + "…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val bytes = ExtensionPackageDownloader.download(entry.downloadUrl)
                                        check(publisherRepository.verifyPackage(entry, bytes, currentApi = 1)) {
                                            "Package digest, publisher signature or API compatibility check failed."
                                        }
                                        bytes
                                    }
                                }
                                result.fold(
                                    { bytes ->
                                        runCatching {
                                            if (Build.VERSION.SDK_INT >= 26 &&
                                                !context.packageManager.canRequestPackageInstalls()) {
                                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                                    .setData(Uri.parse("package:" + context.packageName))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                updaterMessage = "Allow OpenClone to request installs in Android settings, then tap Install again."
                                            } else {
                                                val sessionId = ExtensionPackageInstaller.stage(context, bytes, entry.id)
                                                updaterMessage = "Verified package staged in Android installer session " + sessionId + ". Complete or cancel the system confirmation."
                                                ExtensionDiagnosticsLog(context).append("updater", "package_staged", "A verified extension package was staged for Android installation.")
                                            }
                                        }.onFailure { updaterMessage = it.message ?: "Could not stage package for installation." }
                                    },
                                    { updaterMessage = it.message ?: "Package download or verification failed." }
                                )
                                updaterBusy = false
                            }
                        }) { Text("Install") }
                    }
                }
                installStatus?.let { record ->
                    Text("Last Android install result · session ${record.sessionId} · status ${record.status}: ${record.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { installStatus = ExtensionInstallStatusStore.read(context) }) { Text("Refresh install result") }
                Text(updaterMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        RemoteControlCompanionCard()

        RemoteScreenCaptureControlCard(context)

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🖥️ Remote Control · Secure local session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Opt-in TLS 1.2 server with a pinned device certificate, Android Keystore-encrypted pairing secret, HMAC authentication and replay protection. The API exposes status and launch of OpenClone-created clones only. It does not expose arbitrary shell commands or private app data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (remotePairingInfo == null) {
                    Button(onClick = {
                        runCatching {
                            val pairing = RemoteControlPairingStore.create(context)
                            context.startForegroundService(Intent(context, RemoteControlService::class.java))
                            remotePairingInfo = pairing
                            remoteMessage = "Remote session started. Share the pairing link only with a client you trust; it contains the pairing secret."
                            ExtensionDiagnosticsLog(context).append("remote", "session_started", "User started a TLS-protected remote-control session.")
                        }.onFailure {
                            RemoteControlPairingStore.revoke(context)
                            remotePairingInfo = null
                            remoteMessage = it.message ?: "Could not start remote control."
                        }
                    }) { Text("Start remote control") }
                } else {
                    Text("Listening address: ${remotePairingInfo!!.host}:${remotePairingInfo!!.port}")
                    Text("TLS certificate SHA-256: ${remotePairingInfo!!.certificateFingerprint.chunked(4).joinToString(" ")}", style = MaterialTheme.typography.labelSmall)
                    Text("Pairing link (keep secret):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(remotePairingInfo!!.toUri(), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, remotePairingInfo!!.toUri())
                            context.startActivity(Intent.createChooser(share, "Share secure OpenClone pairing link"))
                        }) { Text("Share pairing link") }
                        TextButton(onClick = {
                            context.stopService(Intent(context, RemoteControlService::class.java))
                            RemoteControlPairingStore.revoke(context)
                            remotePairingInfo = null
                            remoteMessage = "Remote session stopped and pairing credential revoked."
                        }) { Text("Stop & revoke") }
                    }
                }
                Text(remoteMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌐 Proxy Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Save up to 10 unauthenticated HTTP CONNECT or SOCKS5 endpoints and test a tunnel handshake. Assign a profile to a clone in the Per-clone Routing section below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            ExtensionDiagnosticsLog(context).append("proxy", "profile_saved", "Saved a proxy profile.")
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
                                    {
                                        ExtensionDiagnosticsLog(context).append("proxy", "handshake_success", "Proxy tunnel handshake succeeded.")
                                        "Proxy handshake succeeded. Proxy authentication is not configured; use Per-clone Routing to apply this endpoint."
                                    },
                                    {
                                        ExtensionDiagnosticsLog(context).append("proxy", "handshake_failed", "Proxy tunnel handshake failed.")
                                        "Connection failed: " + (it.message ?: "host unreachable")
                                    }
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
                        TextButton(onClick = {
                            repository.delete(p.name)
                            assignmentRepository.removeProfile(p.name)
                            profiles = repository.getAll()
                            proxyAssignments = assignmentRepository.getAll()
                        }) { Text("Delete") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎯 Proxy Manager · Per-clone Routing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Choose a clone and proxy profile. Android's VpnService allowlist sends only that clone's traffic through the tunnel; OpenClone and other apps bypass it. Android permits one active VPN per user/profile, so only one clone can be routed at a time. Existing VPN sessions must be disconnected first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { cloneMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = installedClones.isNotEmpty()) {
                        Text(installedClones.firstOrNull { it.clonePackage == selectedClonePackage }?.let { it.cloneLabel + " · " + it.clonePackage } ?: "Select an installed clone")
                    }
                    DropdownMenu(expanded = cloneMenuExpanded, onDismissRequest = { cloneMenuExpanded = false }) {
                        installedClones.forEach { clone ->
                            DropdownMenuItem(
                                text = { Text(clone.cloneLabel + " · " + clone.clonePackage) },
                                onClick = { selectedClonePackage = clone.clonePackage; cloneMenuExpanded = false }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { profileMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = profiles.isNotEmpty()) {
                        Text(profiles.firstOrNull { it.name == selectedProxyProfile }?.let { it.name + " · " + it.protocol + " " + it.host + ":" + it.port } ?: "Select a proxy profile")
                    }
                    DropdownMenu(expanded = profileMenuExpanded, onDismissRequest = { profileMenuExpanded = false }) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name + " · " + profile.protocol + " " + profile.host + ":" + profile.port) },
                                onClick = { selectedProxyProfile = profile.name; profileMenuExpanded = false }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = installedClones.isNotEmpty() && profiles.isNotEmpty(), onClick = {
                        val clone = installedClones.firstOrNull { it.clonePackage == selectedClonePackage }
                        val profile = profiles.firstOrNull { it.name == selectedProxyProfile }
                        if (clone == null || profile == null) {
                            proxyRoutingMessage = "Select an installed clone and a saved proxy profile."
                        } else if (network.vpn) {
                            proxyRoutingMessage = "Another VPN is active. Stop it in Android settings or stop the current OpenClone route before starting this one."
                        } else {
                            runCatching {
                                assignmentRepository.assign(clone.clonePackage, profile.name)
                                proxyAssignments = assignmentRepository.getAll()
                                val serviceIntent = Intent(context, ProxyVpnService::class.java).apply {
                                    putExtra(ProxyVpnService.EXTRA_CLONE_PACKAGE, clone.clonePackage)
                                    putExtra(ProxyVpnService.EXTRA_PROXY_HOST, profile.host)
                                    putExtra(ProxyVpnService.EXTRA_PROXY_PORT, profile.port)
                                    putExtra(ProxyVpnService.EXTRA_PROXY_PROTOCOL, profile.protocol)
                                }
                                val consent = VpnService.prepare(context)
                                if (consent == null) {
                                    context.startForegroundService(serviceIntent)
                                    proxyRoutingMessage = "Starting route for " + clone.cloneLabel + "…"
                                } else {
                                    pendingProxyIntent = serviceIntent
                                    vpnConsentLauncher.launch(consent)
                                }
                                ExtensionDiagnosticsLog(context).append("proxy", "clone_assignment_saved", "Saved a proxy profile assignment for one clone.")
                            }.onFailure { proxyRoutingMessage = it.message ?: "Could not start per-clone routing." }
                        }
                    }) { Text("Start selected clone route") }
                    TextButton(onClick = {
                        context.startService(Intent(context, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
                        proxyRoutingMessage = "Stop requested for the active clone VPN route."
                        refreshNetwork()
                    }) { Text("Stop route") }
                }
                proxyAssignments.forEach { assignment ->
                    val assignedClone = installedClones.firstOrNull { it.clonePackage == assignment.clonePackage }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(assignedClone?.cloneLabel ?: assignment.clonePackage, fontWeight = FontWeight.Medium)
                            Text("Profile: " + assignment.profileName, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            assignmentRepository.removeClone(assignment.clonePackage)
                            proxyAssignments = assignmentRepository.getAll()
                        }) { Text("Remove") }
                    }
                }
                Text(proxyRoutingMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Check for source-app updates used by installed clones. OpenClone only reports updates; it never installs or updates apps automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Check interval", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24L to "1 day", 72L to "3 days", 168L to "7 days").forEach { (hours, label) ->
                        TextButton(onClick = {
                            runCatching {
                                CloneUpdateAutomation.setIntervalHours(context, hours)
                                automationInterval = hours
                            }.onFailure { message = it.message ?: "Could not update interval." }
                        }) { Text((if (automationInterval == hours) "✓ " else "") + label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (automation) "Daily check enabled" else "Daily check disabled", modifier = Modifier.weight(1f))
                    Switch(checked = automation, onCheckedChange = { enabled ->
                        runCatching {
                            CloneUpdateAutomation.setEnabled(context, enabled)
                            automation = enabled
                            ExtensionDiagnosticsLog(context).append("automation", if (enabled) "enabled" else "disabled", "Source-app update check schedule changed.")
                        }
                            .onFailure { message = it.message ?: "Could not change schedule." }
                    })
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔒 App Lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Protect OpenClone with Android biometrics or the device PIN, pattern or password. No custom password is stored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌍 WebView Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Manage an isolated OpenClone-owned browser. Only exact HTTPS hosts you add are loaded; JavaScript, DOM storage, file/content access and mixed content are disabled. This does not control WebViews inside cloned apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = webViewHostInput,
                    onValueChange = { webViewHostInput = it.take(253) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Allowed host, e.g. docs.example.com") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val candidate = webViewHostInput.trim().lowercase().removePrefix("https://")
                            .substringBefore('/').substringBefore('?').substringBefore('#')
                        val labels = candidate.split('.')
                        val valid = labels.size >= 2 && labels.all { label ->
                            label.isNotBlank() && label.length <= 63 &&
                                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                                label.all { it.isLetterOrDigit() || it == '-' }
                        } && ManagedWebViewPolicy(allowedHosts = setOf(candidate)).allows("https://$candidate/")
                        if (!valid) {
                            webViewMessage = "Enter a valid hostname without a path, port or credentials."
                        } else {
                            val updated = (webViewHosts + candidate).distinct().sorted()
                            context.getSharedPreferences("openclone_managed_webview", Context.MODE_PRIVATE).edit()
                                .putStringSet("allowed_hosts", updated.toSet()).apply()
                            webViewHosts = updated
                            webViewHostInput = ""
                            webViewMessage = "Host added to the HTTPS allowlist."
                        }
                    }) { Text("Add host") }
                    Button(
                        enabled = webViewHosts.isNotEmpty(),
                        onClick = { context.startActivity(Intent(context, ManagedWebViewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    ) { Text("Open managed browser") }
                }
                webViewHosts.forEach { host ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            val updated = webViewHosts - host
                            context.getSharedPreferences("openclone_managed_webview", Context.MODE_PRIVATE).edit()
                                .putStringSet("allowed_hosts", updated.toSet()).apply()
                            webViewHosts = updated
                        }) { Text("Remove") }
                    }
                }
                Text(webViewMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                    showClearCache = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.cacheDir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
                        }
                        ExtensionDiagnosticsLog(context).append("storage", "cache_cleared", "OpenClone temporary cache was cleared.")
                        storage = withContext(Dispatchers.IO) { StorageSnapshot.read(context) }
                    }
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

@Composable
private fun RemoteScreenCaptureControlCard(context: Context) {
    var active by remember {
        mutableStateOf(context.getSharedPreferences(RemoteScreenCaptureService.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(RemoteScreenCaptureService.KEY_ACTIVE, false))
    }
    var message by remember { mutableStateOf("Screen capture starts only after Android's system consent prompt.") }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching {
                val start = Intent(context, RemoteScreenCaptureService::class.java)
                    .putExtra(RemoteScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RemoteScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                androidx.core.content.ContextCompat.startForegroundService(context, start)
                active = true
                message = "Screen capture is active on this device. Remote streaming is not enabled yet."
            }.onFailure {
                active = false
                message = it.message ?: "Could not start screen capture."
            }
        } else message = "Screen capture consent was declined or cancelled."
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📺 Consent-based screen sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("The target phone must approve Android's screen-capture prompt. A persistent notification with a Stop action remains visible while capture is active. This stage captures locally; authenticated network streaming is not enabled yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) {
                Text("Screen capture active on this device", color = MaterialTheme.colorScheme.primary)
                Button(onClick = {
                    context.stopService(Intent(context, RemoteScreenCaptureService::class.java))
                    active = false
                    message = "Screen capture stopped."
                }) { Text("Stop screen capture") }
            } else {
                Button(onClick = {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    launcher.launch(manager.createScreenCaptureIntent())
                }) { Text("Request screen-capture consent") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
