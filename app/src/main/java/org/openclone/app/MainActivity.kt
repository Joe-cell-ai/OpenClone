package org.openclone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openclone.app.clone.ApkCloneEngine
import org.openclone.app.clone.CloneBuildResult
import org.openclone.app.clone.CloneInstaller
import org.openclone.app.data.ApkInspection
import org.openclone.app.data.ApkInspector
import org.openclone.app.data.InstalledApp
import org.openclone.app.data.InstalledAppRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenCloneTheme { OpenCloneHome() } }
    }
}

@Composable
private fun OpenCloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), typography = Typography(), content = content)
}

@Composable
private fun OpenCloneHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var inspection by remember { mutableStateOf<ApkInspection?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var cloneSource by remember { mutableStateOf<InstalledApp?>(null) }
    var cloneName by remember { mutableStateOf("") }
    var clonePackage by remember { mutableStateOf("") }
    var cloneBuilding by remember { mutableStateOf(false) }
    var cloneProgress by remember { mutableStateOf(0) }
    var markCloneIcon by remember { mutableStateOf(true) }
    var cloneError by remember { mutableStateOf<String?>(null) }
    var cloneResult by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingInstall by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingExport by remember { mutableStateOf<CloneBuildResult?>(null) }

    fun refreshApps() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { InstalledAppRepository(context.packageManager).getInstalledApps(includeSystem) }
            }
            apps = result.getOrDefault(emptyList())
            if (result.isFailure) statusMessage = result.exceptionOrNull()?.message ?: "Could not read installed apps."
            loading = false
        }
    }

    fun commitInstall(result: CloneBuildResult) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CloneInstaller.install(context, result) }
            }
            statusMessage = outcome.fold(
                onSuccess = { "Installation request sent for ${result.clonePackage}. Confirm the Android installer prompt." },
                onFailure = { it.message ?: "Could not start the Android installer." }
            )
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val result = pendingInstall
        pendingInstall = null
        if (result != null && context.packageManager.canRequestPackageInstalls()) {
            commitInstall(result)
        } else if (result != null) {
            statusMessage = "Allow OpenClone to install apps, then try again."
        }
    }

    fun installClone(result: CloneBuildResult) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingInstall = result
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            installPermissionLauncher.launch(settingsIntent)
        } else {
            commitInstall(result)
        }
    }

    val importApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loading = true
            scope.launch {
                inspection = runCatching {
                    withContext(Dispatchers.IO) { ApkInspector(context).inspect(uri) }
                }.getOrElse {
                    statusMessage = it.message ?: "Could not inspect the selected APK."
                    null
                }
                loading = false
            }
        }
    }

    val exportClone = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val result = pendingExport
        pendingExport = null
        if (uri != null && result != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            ZipOutputStream(output).use { zip ->
                                result.apks.forEachIndexed { index, file ->
                                    zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else "split_$index.apk"))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        } ?: error("Could not create the selected destination file.")
                    }
                }
                statusMessage = outcome.fold(
                    onSuccess = { "Clone APK set exported as a ZIP archive." },
                    onFailure = { it.message ?: "Clone export failed." }
                )
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.getStringExtra(CloneInstaller.EXTRA_PACKAGE_NAME).orEmpty()
                val status = intent.getIntExtra(CloneInstaller.EXTRA_STATUS, -1)
                val detail = intent.getStringExtra(CloneInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                statusMessage = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    "Clone installed successfully: $packageName"
                } else {
                    "Installation failed for $packageName${if (detail.isBlank()) "" else ": $detail"}"
                }
                refreshApps()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(CloneInstaller.ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun startCloneBuild() {
        val source = cloneSource ?: return
        val targetPackage = clonePackage.trim()
        val targetLabel = cloneName.trim()
        cloneError = null
        if (!ApkCloneEngine.PACKAGE_NAME.matches(targetPackage)) {
            cloneError = "Enter a valid package ID, such as com.example.myclone."
            return
        }
        if (targetLabel.isBlank() || targetLabel.length > 100) {
            cloneError = "The app name must contain 1–100 characters."
            return
        }
        cloneBuilding = true
        cloneProgress = 0
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ApkCloneEngine(context).buildInstalled(
                        applicationInfo = source.applicationInfo,
                        originalPackage = source.packageName,
                        newPackage = targetPackage,
                        newLabel = targetLabel,
                        outputDirectory = File(context.cacheDir, "openclone/builds/$targetPackage"),
                        onProgress = { value -> scope.launch { cloneProgress = value } },
                        markIcon = markCloneIcon
                    )
                }
            }
            cloneBuilding = false
            outcome.fold(
                onSuccess = {
                    cloneResult = it
                    cloneSource = null
                    statusMessage = "Clone APK set built and signature-verified."
                },
                onFailure = {
                    cloneError = it.message ?: "Clone build failed."
                }
            )
        }
    }

    LaunchedEffect(includeSystem) { refreshApps() }

    val visibleApps = remember(apps, query) {
        apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text("OpenClone", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Installed app browser", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { importApk.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text("Inspect APK")
                }
                Button(onClick = { refreshApps() }, modifier = Modifier.weight(1f)) {
                    Text("Refresh apps")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search installed apps") }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Include system apps")
                Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading app information…")
                }
                visibleApps.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { Text("No matching apps found.") }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        InstalledAppRow(app = app, onClick = { selectedApp = app })
                    }
                }
            }

            Text(
                "Clone builds rewrite the APK manifest/resources and re-sign the result. Some apps may not run after cloning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            statusMessage?.let { message ->
                TextButton(onClick = { statusMessage = null }) { Text(message) }
            }
        }
    }

    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("Package", app.packageName)
                    DetailLine("Version", app.versionName ?: "Unknown")
                    DetailLine("Version code", app.versionCode.toString())
                    DetailLine("APK components", app.apkPartCount.toString())
                    DetailLine("Type", if (app.isSystemApp) "System app" else "User app")
                    Text(
                        "OpenClone rebuilds the base APK and installed split APKs with a new package ID and signing key. App-specific signature or package checks may still prevent launch.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cloneSource = app
                    cloneName = "${app.label} Clone".take(100)
                    clonePackage = suggestedClonePackage(app.packageName, context.packageManager)
                    cloneError = null
                    markCloneIcon = true
                    selectedApp = null
                }) { Text("Create clone") }
            },
            dismissButton = { TextButton(onClick = { selectedApp = null }) { Text("Close") } }
        )
    }

    cloneSource?.let { source ->
        AlertDialog(
            onDismissRequest = { if (!cloneBuilding) cloneSource = null },
            title = { Text("Customize clone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Clone app name") }
                    )
                    OutlinedTextField(
                        value = clonePackage,
                        onValueChange = { clonePackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("New package ID") }
                    )
                    Text(
                        "Original: ${source.packageName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    cloneError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (cloneBuilding) {
                        LinearProgressIndicator(
                            progress = { cloneProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Building and signing APKs… $cloneProgress%")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Mark clone icon with a dot")
                        Switch(checked = markCloneIcon, onCheckedChange = { markCloneIcon = it })
                    }
                    Text(
                        "A new signing key is generated for this OpenClone installation and kept encrypted in app-private storage. Cloned apps will not share data with the original.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { startCloneBuild() }, enabled = !cloneBuilding) {
                    Text(if (cloneBuilding) "Building…" else "Build clone")
                }
            },
            dismissButton = {
                TextButton(onClick = { cloneSource = null }, enabled = !cloneBuilding) { Text("Cancel") }
            }
        )
    }

    cloneResult?.let { result ->
        AlertDialog(
            onDismissRequest = { cloneResult = null },
            title = { Text("Clone ready") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("App name", result.cloneLabel)
                    DetailLine("Package ID", result.clonePackage)
                    DetailLine("APK files", result.apks.size.toString())
                    DetailLine("Icon badge", if (result.iconBadgeApplied) "Applied" else "Original icon kept")
                    Text(
                        "All output APKs passed signature verification. Android will still show its own install confirmation. Some apps depend on their original signature, package name, or server registration and may not work as clones.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { installClone(result) }) { Text("Install clone") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingExport = result
                        exportClone.launch("${result.clonePackage}-clone-apk-set.zip")
                    }) { Text("Export") }
                    TextButton(onClick = { cloneResult = null }) { Text("Close") }
                }
            }
        )
    }

    inspection?.let { apk ->
        AlertDialog(
            onDismissRequest = { inspection = null },
            title = { Text("APK information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("File", apk.fileName)
                    DetailLine("App name", apk.label)
                    DetailLine("Package", apk.packageName)
                    DetailLine("Version", apk.versionName ?: "Unknown")
                    DetailLine("Version code", apk.versionCode.toString())
                    DetailLine("Minimum SDK", apk.minSdk.toString())
                    DetailLine("Target SDK", apk.targetSdk.toString())
                    DetailLine("Requested permissions", apk.requestedPermissions.size.toString())
                    Text(
                        "This inspects one APK file. Split APK installation bundles are not yet supported by the inspector.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { inspection = null }) { Text("Done") } }
        )
    }
}

@Suppress("DEPRECATION")
private fun suggestedClonePackage(original: String, packageManager: android.content.pm.PackageManager): String {
    var suffix = 1
    var candidate = "$original.clone"
    while (runCatching { packageManager.getApplicationInfo(candidate, 0) }.isSuccess) {
        suffix++
        candidate = "$original.clone$suffix"
    }
    return candidate
}

@Composable
private fun InstalledAppRow(app: InstalledApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val iconBitmap = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.applicationInfo)
                .toBitmap(width = 96, height = 96).asImageBitmap()
        }.getOrNull()
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconBitmap != null) {
                Image(iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Text("A", modifier = Modifier.size(44.dp), style = MaterialTheme.typography.headlineMedium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    "${if (app.isSystemApp) "System" else "Installed"} · ${app.apkPartCount} APK part(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
