package org.openclone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openclone.app.clone.ApkCloneEngine
import org.openclone.app.clone.BadgePosition
import org.openclone.app.clone.CloneBuildResult
import org.openclone.app.clone.CloneInstaller
import org.openclone.app.clone.CloneBuildWorker
import org.openclone.app.clone.SigningKeys
import org.openclone.app.data.ApkInspection
import org.openclone.app.data.ApkInspector
import org.openclone.app.data.ExternalApkSet
import org.openclone.app.data.ExternalApkImporter
import org.openclone.app.data.InstalledApp
import org.openclone.app.data.InstalledAppRepository
import org.openclone.app.data.InstalledClone
import org.openclone.app.data.InstalledCloneRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenCloneTheme { OpenCloneHome() } }
    }
}

private const val CLONE_WORK_PREFERENCES = "openclone_clone_work"
private const val ACTIVE_CLONE_WORK_ID = "active_clone_work_id"

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
    var externalSource by remember { mutableStateOf<ExternalApkSet?>(null) }
    var inspection by remember { mutableStateOf<ApkInspection?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showClones by remember { mutableStateOf(false) }
    var clones by remember { mutableStateOf<List<InstalledClone>>(emptyList()) }
    var selectedClone by remember { mutableStateOf<InstalledClone?>(null) }

    var cloneSource by remember { mutableStateOf<InstalledApp?>(null) }
    var cloneName by remember { mutableStateOf("") }
    var clonePackage by remember { mutableStateOf("") }
    var cloneBuilding by remember { mutableStateOf(false) }
    var cloneWorkId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloneWorkInfo by remember { mutableStateOf<WorkInfo?>(null) }
    var cloneProgress by remember { mutableStateOf(0) }
    var markCloneIcon by remember { mutableStateOf(true) }
    var customIconPath by rememberSaveable { mutableStateOf<String?>(null) }
    var badgePosition by remember { mutableStateOf(BadgePosition.BOTTOM_RIGHT) }
    var cloneError by remember { mutableStateOf<String?>(null) }
    var cloneUpdateMode by remember { mutableStateOf(false) }
    var cloneResult by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingInstall by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingExport by remember { mutableStateOf<CloneBuildResult?>(null) }
    var showSigningKeys by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var signingAliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeSigningAlias by remember { mutableStateOf<String?>(null) }
    var signingAliasInput by remember { mutableStateOf("") }
    var signingPassword by remember { mutableStateOf("") }
    var pendingSigningExportAlias by remember { mutableStateOf<String?>(null) }
    var signingMessage by remember { mutableStateOf<String?>(null) }

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

    val chooseCustomIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                total += count
                                require(total <= 10 * 1024 * 1024) { "Choose an image smaller than 10 MB." }
                                buffer.write(chunk, 0, count)
                            }
                            buffer.toByteArray()
                        } ?: error("Could not read the selected image.")
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("The selected file is not a supported image.")
                        require(bitmap.width in 1..4096 && bitmap.height in 1..4096) {
                            "The image dimensions must not exceed 4096 × 4096."
                        }
                        val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
                        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
                        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
                        val destination = File(context.filesDir, "openclone/custom-icon.png")
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { output ->
                            check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "Could not save the custom icon."
                            }
                        }
                        destination.absolutePath
                    }
                }
                outcome.onSuccess {
                    customIconPath = it
                    statusMessage = "Custom launcher icon uploaded and preview is ready."
                }.onFailure {
                    statusMessage = it.message ?: "Could not load the custom icon."
                }
            }
        }
    }

    val importBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loading = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ExternalApkImporter(context).import(uri) }
                }
                result.fold(
                    onSuccess = { source ->
                        externalSource = source
                        cloneSource = null
                        cloneName = "${source.label} Clone".take(100)
                        clonePackage = suggestedClonePackage(source.packageName, context.packageManager)
                        cloneError = null
                        cloneUpdateMode = false
                        markCloneIcon = true
                        customIconPath = null
                        badgePosition = BadgePosition.BOTTOM_RIGHT
                        statusMessage = "Imported ${source.apkFiles.size} APK component(s) from ${source.archiveName}."
                    },
                    onFailure = { statusMessage = it.message ?: "Could not import this APK bundle." }
                )
                loading = false
            }
        }
    }

    val exportSigningIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri: Uri? ->
        val alias = pendingSigningExportAlias
        pendingSigningExportAlias = null
        if (uri != null && alias != null) {
            val password = signingPassword.toCharArray()
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                SigningKeys.get(context).export(alias, output, password)
                            } ?: error("Could not open the selected backup destination.")
                        } finally {
                            password.fill('\u0000')
                        }
                    }
                }
                signingPassword = ""
                signingMessage = outcome.fold(
                    onSuccess = { "Signing identity backup exported." },
                    onFailure = { it.message ?: "Could not export the signing identity." }
                )
            }
        } else {
            signingPassword = ""
        }
    }

    val importSigningIdentity = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val identityAlias = signingAliasInput.trim()
            val password = signingPassword.toCharArray()
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                SigningKeys.get(context).import(input, password, "", identityAlias)
                            } ?: error("Could not open the selected signing backup.")
                        } finally {
                            password.fill('\u0000')
                        }
                    }
                }
                signingPassword = ""
                outcome.onSuccess {
                    val keys = SigningKeys.get(context)
                    signingAliases = keys.identityAliases
                    activeSigningAlias = keys.activeAlias
                    signingMessage = "Signing identity restored and encrypted on this device."
                }.onFailure {
                    signingMessage = it.message ?: "Could not restore the signing identity."
                }
            }
        } else {
            signingPassword = ""
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
                val operation = intent.getStringExtra(CloneInstaller.EXTRA_OPERATION).orEmpty()
                val status = intent.getIntExtra(CloneInstaller.EXTRA_STATUS, -1)
                val detail = intent.getStringExtra(CloneInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                statusMessage = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    if (operation == "uninstall") "Clone uninstalled: $packageName" else "Clone installed successfully: $packageName"
                } else {
                    "${if (operation == "uninstall") "Uninstall" else "Installation"} failed for $packageName${if (detail.isBlank()) "" else ": $detail"}"
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
        val installedSource = cloneSource
        val importedSource = externalSource
        if (installedSource == null && importedSource == null) return
        val sourcePackage = installedSource?.packageName ?: importedSource!!.packageName
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

        val sourceApks = installedSource?.let { source ->
            (listOfNotNull(source.applicationInfo.sourceDir) + source.applicationInfo.splitSourceDirs.orEmpty()).map(::File)
        } ?: importedSource!!.apkFiles
        val input = Data.Builder()
            .putStringArray(CloneBuildWorker.KEY_SOURCE_APKS, sourceApks.map { it.absolutePath }.toTypedArray())
            .putString(CloneBuildWorker.KEY_ORIGINAL_PACKAGE, sourcePackage)
            .putString(CloneBuildWorker.KEY_CLONE_PACKAGE, targetPackage)
            .putString(CloneBuildWorker.KEY_CLONE_LABEL, targetLabel)
            .putBoolean(CloneBuildWorker.KEY_MARK_ICON, markCloneIcon && installedSource != null)
            .putBoolean(CloneBuildWorker.KEY_ALLOW_UPDATE, cloneUpdateMode)
            .putString(CloneBuildWorker.KEY_ICON_PACKAGE, installedSource?.packageName)
            .putString(CloneBuildWorker.KEY_CUSTOM_ICON_PATH, customIconPath.takeIf { installedSource != null })
            .putString(CloneBuildWorker.KEY_BADGE_POSITION, badgePosition.name)
            .build()
        val request = OneTimeWorkRequestBuilder<CloneBuildWorker>()
            .setInputData(input)
            .build()
        val workPreferences = context.getSharedPreferences(CLONE_WORK_PREFERENCES, Context.MODE_PRIVATE)
        if (!workPreferences.edit().putString(ACTIVE_CLONE_WORK_ID, request.id.toString()).commit()) {
            cloneError = "Could not save background build tracking. Please try again."
            return
        }
        try {
            WorkManager.getInstance(context).enqueue(request)
        } catch (error: Exception) {
            workPreferences.edit().remove(ACTIVE_CLONE_WORK_ID).apply()
            cloneError = error.message ?: "Could not queue the background clone build."
            return
        }
        cloneWorkId = request.id.toString()
        cloneWorkInfo = null
        cloneBuilding = true
        cloneProgress = 0
    }

    DisposableEffect(context, cloneWorkId) {
        val workId = cloneWorkId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (workId == null) {
            onDispose { }
        } else {
            val liveData = WorkManager.getInstance(context).getWorkInfoByIdLiveData(workId)
            val observer = Observer<WorkInfo?> { info ->
                if (info != null) {
                    cloneWorkInfo = info
                    cloneBuilding = !info.state.isFinished
                    cloneProgress = info.progress.getInt(CloneBuildWorker.KEY_PROGRESS, cloneProgress)
                    if (info.state.isFinished) {
                        context.getSharedPreferences(CLONE_WORK_PREFERENCES, Context.MODE_PRIVATE)
                            .edit()
                            .remove(ACTIVE_CLONE_WORK_ID)
                            .apply()
                        cloneBuilding = false
                        cloneWorkId = null
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                cloneUpdateMode = info.outputData.getBoolean(CloneBuildWorker.KEY_ALLOW_UPDATE, false)
                                val outputFiles = info.outputData.getStringArray(CloneBuildWorker.KEY_OUTPUT_APKS)
                                    ?.map(::File).orEmpty()
                                if (outputFiles.isNotEmpty() && outputFiles.all { it.isFile }) {
                                    cloneResult = CloneBuildResult(
                                        originalPackage = info.outputData.getString(CloneBuildWorker.KEY_ORIGINAL_PACKAGE).orEmpty(),
                                        clonePackage = info.outputData.getString(CloneBuildWorker.KEY_CLONE_PACKAGE).orEmpty(),
                                        cloneLabel = info.outputData.getString(CloneBuildWorker.KEY_CLONE_LABEL).orEmpty(),
                                        apks = outputFiles,
                                        iconBadgeApplied = info.outputData.getBoolean(CloneBuildWorker.KEY_ICON_BADGE_APPLIED, false),
                                        customIconApplied = info.outputData.getBoolean(CloneBuildWorker.KEY_CUSTOM_ICON_APPLIED, false)
                                    )
                                    cloneSource = null
                                    externalSource = null
                                    statusMessage = "Clone APK set built and signature-verified."
                                } else {
                                    cloneError = "The background build finished without all expected APK files."
                                    statusMessage = cloneError
                                }
                            }
                            WorkInfo.State.CANCELLED -> {
                                cloneError = "Clone build cancelled."
                                statusMessage = cloneError
                            }
                            WorkInfo.State.FAILED -> {
                                cloneError = info.outputData.getString(CloneBuildWorker.KEY_ERROR)
                                    ?: "Clone build failed."
                                statusMessage = cloneError
                            }
                            else -> Unit
                        }
                    }
                }
            }
            liveData.observeForever(observer)
            onDispose { liveData.removeObserver(observer) }
        }
    }

    LaunchedEffect(Unit) {
        if (cloneWorkId == null) {
            val preferences = context.getSharedPreferences(CLONE_WORK_PREFERENCES, Context.MODE_PRIVATE)
            val savedWorkId = preferences.getString(ACTIVE_CLONE_WORK_ID, null)
            val workId = savedWorkId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (workId == null) {
                if (preferences.getString(ACTIVE_CLONE_WORK_ID, null) == savedWorkId) {
                    preferences.edit().remove(ACTIVE_CLONE_WORK_ID).apply()
                }
            } else {
                val workLookup = withContext(Dispatchers.IO) {
                    runCatching { WorkManager.getInstance(context).getWorkInfoById(workId).get() }
                }
                if (
                    cloneWorkId == null &&
                    preferences.getString(ACTIVE_CLONE_WORK_ID, null) == savedWorkId
                ) {
                    when {
                        workLookup.isFailure -> cloneWorkId = savedWorkId
                        workLookup.getOrNull() == null -> preferences.edit().remove(ACTIVE_CLONE_WORK_ID).apply()
                        else -> cloneWorkId = savedWorkId
                    }
                }
            }
        }
    }

    LaunchedEffect(includeSystem) { refreshApps() }
    LaunchedEffect(apps) {
        clones = withContext(Dispatchers.IO) { InstalledCloneRepository(context.packageManager).findClones(apps) }
    }

    val customIconPreview = remember(customIconPath) {
        customIconPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
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
            TextButton(onClick = {
                val keys = SigningKeys.get(context)
                signingAliases = keys.identityAliases
                activeSigningAlias = keys.activeAlias
                showSigningKeys = true
            }) { Text("Signing identities") }
            TextButton(onClick = { showAbout = true }) { Text("About & licenses") }
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { importApk.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text("Inspect APK")
                }
                Button(onClick = { refreshApps() }, modifier = Modifier.weight(1f)) {
                    Text("Refresh apps")
                }
            }
            Button(
                onClick = { importBundle.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import APK / bundle to clone") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showClones = false }) { Text("Installed apps") }
                TextButton(onClick = { showClones = true }) { Text("My clones (${clones.size})") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (showClones) "Search clones" else "Search installed apps") }
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
                showClones && clones.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { Text("No OpenClone clones found yet.") }
                showClones -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(clones.filter { it.cloneLabel.contains(query, true) || it.clonePackage.contains(query, true) }, key = { it.clonePackage }) { clone ->
                        InstalledAppRow(app = clone.app, onClick = { selectedClone = clone }, subtitle = clone.updateStatus)
                    }
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
                    customIconPath = null
                    badgePosition = BadgePosition.BOTTOM_RIGHT
                    cloneUpdateMode = false
                    selectedApp = null
                }) { Text("Create clone") }
            },
            dismissButton = { TextButton(onClick = { selectedApp = null }) { Text("Close") } }
        )
    }

    if (cloneSource != null || externalSource != null) {
        val sourcePackage = cloneSource?.packageName ?: externalSource!!.packageName
        val sourceLabel = cloneSource?.label ?: externalSource!!.label
        AlertDialog(
            onDismissRequest = { if (!cloneBuilding) { cloneSource = null; externalSource = null; cloneUpdateMode = false } },
            title = { Text(if (cloneUpdateMode) "Update clone" else "Customize clone") },
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
                        "Original: $sourcePackage · $sourceLabel",
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
                    if (cloneSource != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { chooseCustomIcon.launch(arrayOf("image/*")) }) {
                                Text(if (customIconPath == null) "Upload custom icon" else "Change custom icon")
                            }
                            if (customIconPath != null) {
                                TextButton(onClick = { customIconPath = null }) { Text("Remove") }
                            }
                        }
                        customIconPreview?.let { preview ->
                            Image(preview, contentDescription = "Custom launcher icon preview", modifier = Modifier.size(56.dp))
                        }
                        Text(
                            "Custom images replace direct raster PNG launcher icons. Adaptive-icon XML and vector-based launcher icons keep their original artwork.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (markCloneIcon && cloneSource != null) {
                        Text("Badge position", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                            BadgePosition.values().forEach { position ->
                                TextButton(onClick = { badgePosition = position }) {
                                    Text((if (badgePosition == position) "• " else "") + when (position) {
                                        BadgePosition.TOP_LEFT -> "Top left"
                                        BadgePosition.TOP_RIGHT -> "Top right"
                                        BadgePosition.BOTTOM_LEFT -> "Bottom left"
                                        BadgePosition.BOTTOM_RIGHT -> "Bottom right"
                                    })
                                }
                            }
                        }
                    }
                    Text(
                        if (cloneUpdateMode) "The update is signed with the same OpenClone key. Android should preserve this clone's existing data if the update is accepted." else "A new signing key is generated for this OpenClone installation and kept encrypted in app-private storage. Cloned apps will not share data with the original.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { startCloneBuild() }, enabled = !cloneBuilding) {
                    Text(if (cloneBuilding) "Building…" else if (cloneUpdateMode) "Build update" else "Build clone")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (cloneBuilding) {
                            cloneWorkId?.let { savedId -> runCatching { UUID.fromString(savedId) }.getOrNull()?.let { WorkManager.getInstance(context).cancelWorkById(it) } }
                        } else {
                            cloneSource = null
                            externalSource = null
                            cloneUpdateMode = false
                        }
                    }
                ) { Text(if (cloneBuilding) "Cancel build" else "Cancel") }
            }
        )
    }

    if (cloneWorkId != null && cloneSource == null && externalSource == null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Clone build in progress") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { cloneProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Building and signing APKs… $cloneProgress%")
                    Text(
                        "This build continues in the background. You can cancel it here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cloneWorkId?.let { savedId ->
                        runCatching { UUID.fromString(savedId) }.getOrNull()?.let {
                            WorkManager.getInstance(context).cancelWorkById(it)
                        }
                    }
                }) { Text("Cancel build") }
            }
        )
    }

    cloneResult?.let { result ->
        AlertDialog(
            onDismissRequest = { cloneResult = null; cloneUpdateMode = false },
            title = { Text("Clone ready") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("App name", result.cloneLabel)
                    DetailLine("Package ID", result.clonePackage)
                    DetailLine("APK files", result.apks.size.toString())
                    DetailLine("Icon badge", if (result.iconBadgeApplied) "Applied" else "Not applied")
                    if (customIconPath != null) DetailLine("Custom icon", if (result.customIconApplied) "Applied" else "Not supported by this app's icon resources")
                    Text(
                        "All output APKs passed signature verification. Android will still show its own install confirmation. Some apps depend on their original signature, package name, or server registration and may not work as clones.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { installClone(result) }) { Text(if (cloneUpdateMode) "Install update" else "Install clone") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingExport = result
                        exportClone.launch("${result.clonePackage}-clone-apk-set.zip")
                    }) { Text("Export") }
                    TextButton(onClick = { cloneResult = null; cloneUpdateMode = false }) { Text("Close") }
                }
            }
        )
    }

    selectedClone?.let { clone ->
        AlertDialog(
            onDismissRequest = { selectedClone = null },
            title = { Text(clone.cloneLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("Clone package", clone.clonePackage)
                    DetailLine("Original package", clone.originalPackage)
                    DetailLine(
                        "Created",
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(clone.createdAtMillis))
                    )
                    DetailLine("Signing key", clone.signingFingerprint.take(20) + "…")
                    DetailLine("Clone source version", clone.sourceVersionCode.takeIf { it > 0L }?.toString() ?: "Unknown")
                    DetailLine("Installed original version", clone.currentOriginalVersionCode?.toString() ?: "Original app not installed")
                    DetailLine("Update status", clone.updateStatus)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(clone.clonePackage)
                    if (launchIntent != null) context.startActivity(launchIntent)
                    else statusMessage = "No launcher activity was found for this clone."
                }) { Text("Open clone") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val cachedApps = apps
                        scope.launch {
                            val original = withContext(Dispatchers.IO) {
                                cachedApps.firstOrNull { it.packageName == clone.originalPackage }
                                    ?: runCatching {
                                        InstalledAppRepository(context.packageManager)
                                            .getInstalledApps(includeSystemApps = true)
                                            .firstOrNull { it.packageName == clone.originalPackage }
                                    }.getOrNull()
                            }
                            if (original == null) {
                                statusMessage = "The original app must be installed before this clone can be updated."
                            } else {
                                cloneSource = original
                                cloneName = clone.cloneLabel
                                clonePackage = clone.clonePackage
                                cloneUpdateMode = true
                                cloneError = null
                                customIconPath = null
                                badgePosition = BadgePosition.BOTTOM_RIGHT
                                selectedClone = null
                            }
                        }
                    }) { Text("Update") }
                    TextButton(onClick = {
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { CloneInstaller.uninstall(context, clone.clonePackage) }
                            }
                            statusMessage = outcome.fold(
                                onSuccess = { "Uninstall request sent. Confirm the Android system prompt." },
                                onFailure = { it.message ?: "Could not start uninstall." }
                            )
                            selectedClone = null
                        }
                    }) { Text("Uninstall") }
                    TextButton(onClick = { selectedClone = null }) { Text("Close") }
                }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About OpenClone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OpenClone is free software licensed under GNU General Public License version 3 only (GPL-3.0-only).")
                    Text("This program comes without warranty, to the extent permitted by applicable law.")
                    Text("APK rebuilding components are adapted from APK Toolbox by bl3xand. ARSCLib is licensed under Apache License 2.0.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAbout = false
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Joe-cell-ai/OpenClone/blob/main/LICENSE")))
                    }.onFailure { statusMessage = "Could not open the license page." }
                }) { Text("View GPL license") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showAbout = false
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Joe-cell-ai/OpenClone/blob/main/THIRD_PARTY_NOTICES.md")))
                        }.onFailure { statusMessage = "Could not open third-party notices." }
                    }) { Text("Third-party notices") }
                    TextButton(onClick = { showAbout = false }) { Text("Close") }
                }
            }
        )
    }

    if (showSigningKeys) {
        AlertDialog(
            onDismissRequest = { showSigningKeys = false; signingPassword = "" },
            title = { Text("Signing identities") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select the signing identity used for new clones and updates.")
                    if (signingAliases.isEmpty()) {
                        Text("No custom identities are stored yet.")
                    }
                    signingAliases.forEach { alias ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = activeSigningAlias == alias,
                                onClick = {
                                    runCatching { SigningKeys.get(context).select(alias) }
                                        .onSuccess {
                                            activeSigningAlias = alias
                                            signingMessage = "Active identity changed."
                                        }
                                        .onFailure { signingMessage = it.message }
                                }
                            )
                            Text(alias)
                        }
                    }
                    OutlinedTextField(
                        value = signingAliasInput,
                        onValueChange = { signingAliasInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("New identity name") }
                    )
                    OutlinedTextField(
                        value = signingPassword,
                        onValueChange = { signingPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Backup password") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            if (signingPassword.isBlank()) {
                                signingMessage = "Enter the backup password first."
                            } else {
                                importSigningIdentity.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
                            }
                        }) { Text("Restore backup") }
                        TextButton(onClick = {
                            val alias = activeSigningAlias
                            if (alias == null) signingMessage = "Select a signing identity first."
                            else if (signingPassword.isBlank()) signingMessage = "Enter the backup password first."
                            else {
                                pendingSigningExportAlias = alias
                                exportSigningIdentity.launch("openclone-signing-identity.p12")
                            }
                        }) { Text("Export backup") }
                    }
                    TextButton(onClick = {
                        val alias = signingAliasInput.trim()
                        if (alias.isBlank()) {
                            signingMessage = "Enter an identity name."
                        } else {
                            runCatching { SigningKeys.get(context).generate(alias, "OpenClone " + alias) }
                                .onSuccess {
                                    val keys = SigningKeys.get(context)
                                    signingAliases = keys.identityAliases
                                    activeSigningAlias = keys.activeAlias
                                    signingAliasInput = ""
                                    signingMessage = "New identity created and selected."
                                }
                                .onFailure { signingMessage = it.message }
                        }
                    }) { Text("Create identity") }
                    signingMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        "Private keys are encrypted with Android Keystore. PKCS#12 password-protected backup and restore are supported by the signing-key service.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSigningKeys = false; signingPassword = "" }) { Text("Done") } }
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
private fun InstalledAppRow(app: InstalledApp, onClick: () -> Unit, subtitle: String? = null) {
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
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it == "Update available") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
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
