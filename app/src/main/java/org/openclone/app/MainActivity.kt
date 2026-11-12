package org.openclone.app

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var pendingExport by remember { mutableStateOf<InstalledApp?>(null) }

    fun refreshApps() {
        loading = true
        scope.launch {
            apps = withContext(Dispatchers.IO) {
                InstalledAppRepository(context.packageManager).getInstalledApps(includeSystem)
            }
            loading = false
        }
    }

    LaunchedEffect(includeSystem) { refreshApps() }

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

    val exportApkSet = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val app = pendingExport
        pendingExport = null
        if (uri != null && app != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val info = app.applicationInfo
                        val paths = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
                        require(paths.isNotEmpty()) { "No APK files were reported for this app." }
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            ZipOutputStream(output).use { zip ->
                                paths.forEachIndexed { index, path ->
                                    val file = File(path)
                                    require(file.isFile) { "An APK component is not accessible: $path" }
                                    zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else "split-$index.apk"))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        } ?: error("Could not create the destination file.")
                    }
                }
                statusMessage = result.fold(
                    onSuccess = { "APK set exported. Extract the ZIP to access its APK files." },
                    onFailure = { it.message ?: "APK export failed." }
                )
            }
        }
    }

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
                    Text(" Inspect APK")
                }
                Button(onClick = { refreshApps() }, modifier = Modifier.weight(1f)) {
                    Text(" Refresh apps")
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
            androidx.compose.material3.HorizontalDivider()
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
                "APK cloning and package rebuilding are not implemented yet.",
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
                        "Export creates a ZIP containing the base APK and available split APK files. It does not create a modified clone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingExport = app
                    exportApkSet.launch("${app.packageName}-apk-set.zip")
                }) { Text("Export APK set") }
            },
            dismissButton = { TextButton(onClick = { selectedApp = null }) { Text("Close") } }
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
