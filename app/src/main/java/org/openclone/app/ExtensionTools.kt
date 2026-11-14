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

data class ProxyProfile(val name: String, val host: String, val port: Int) {
    companion object {
        fun validate(name: String, host: String, port: Int) {
            require(name.trim().isNotEmpty() && name.length <= 40) { "Profile name must contain 1–40 characters." }
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "Enter a valid proxy host." }
            require(port in 1..65535) { "Port must be between 1 and 65535." }
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
                val profile = ProxyProfile(item.optString("name"), item.optString("host"), item.optInt("port"))
                runCatching { ProxyProfile.validate(profile.name, profile.host, profile.port) }.onSuccess { add(profile) }
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
    var message by remember { mutableStateOf("Saved profiles are not applied to cloned apps or device traffic.") }
    var network by remember { mutableStateOf(NetworkSnapshot.read(context)) }
    var automation by remember { mutableStateOf(CloneUpdateAutomation.isEnabled(context)) }
    var appLockEnabled by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(APP_LOCK, false)) }
    var appLockMessage by remember { mutableStateOf("Uses the Android device screen lock; OpenClone does not store a separate password.") }

    LaunchedEffect(Unit) { OpenCloneNotificationChannels.ensure(context); network = NetworkSnapshot.read(context) }

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
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌐 Proxy Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Save up to 10 named proxy endpoints and test TCP reachability. This does not route clone traffic or change Android's device-wide proxy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Profile name") })
                OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Proxy host") })
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            repository.save(ProxyProfile(name, host, port.toInt()))
                            profiles = repository.getAll()
                            message = "Proxy profile saved."
                        }.onFailure { message = it.message ?: "Could not save profile." }
                    }) { Text("Save") }
                    TextButton(onClick = {
                        val targetPort = port.toIntOrNull()
                        if (host.isBlank() || targetPort !in 1..65535) message = "Enter a valid host and port."
                        else {
                            message = "Testing TCP connection…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { Socket().use { it.connect(InetSocketAddress(host.trim(), targetPort!!), 4000) } }
                                }
                                message = result.fold(
                                    { "TCP connection succeeded; proxy authentication and clone routing are not tested." },
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
                            Text(p.host + ":" + p.port, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { name = p.name; host = p.host; port = p.port.toString() }) { Text("Edit") }
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
        ExtensionStatusCard("🌍 WebView Manager", "Not applicable yet: OpenClone currently hosts no WebView. It cannot control WebViews inside cloned apps.")
        ExtensionStatusCard("🖥️ Remote Control", "Disabled until secure authenticated pairing, encrypted IPC, explicit consent and revocation are implemented.")
        ExtensionStatusCard("🔄 Extension Updater", "Disabled until a trusted publisher key and signed catalogue/package verification are provisioned. No downloaded code is executed.")
    }
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
