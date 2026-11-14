package org.openclone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import org.openclone.app.data.CloneBuildHistoryRepository
import org.openclone.app.data.CloneProfileRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val THEME_PREFERENCES = "openclone_appearance"
private const val THEME_MODE_KEY = "theme_mode"
private const val CLONE_WORK_PREFERENCES = "openclone_clone_work"
private const val ACTIVE_CLONE_WORK_ID = "active_clone_work_id"

private enum class OpenCloneThemeMode(val label: String) {
    SYSTEM("System default"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember {
                mutableStateOf(
                    runCatching {
                        OpenCloneThemeMode.valueOf(
                            getSharedPreferences(THEME_PREFERENCES, MODE_PRIVATE)
                                .getString(THEME_MODE_KEY, OpenCloneThemeMode.DARK.name)!!
                        )
                    }.getOrDefault(OpenCloneThemeMode.DARK)
                )
            }
            OpenCloneTheme(themeMode) {
                OpenCloneHome(themeMode) { selected ->
                    themeMode = selected
                    getSharedPreferences(THEME_PREFERENCES, MODE_PRIVATE).edit()
                        .putString(THEME_MODE_KEY, selected.name).apply()
                }
            }
        }
    }
}

@Composable
private fun OpenCloneTheme(mode: OpenCloneThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        OpenCloneThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        OpenCloneThemeMode.LIGHT -> false
        OpenCloneThemeMode.DARK, OpenCloneThemeMode.AMOLED -> true
    }
    val colors = when {
        mode == OpenCloneThemeMode.AMOLED -> darkColorScheme(
            primary = Color(0xFF9B8CFF), onPrimary = Color(0xFF100D2B),
            primaryContainer = Color(0xFF342B78), onPrimaryContainer = Color(0xFFE7E3FF),
            secondary = Color(0xFFB18CFF), onSecondary = Color(0xFF21113D),
            tertiary = Color(0xFF55D8E8), background = Color.Black,
            surface = Color(0xFF050505), surfaceVariant = Color(0xFF171717),
            onSurface = Color(0xFFF1F1F7), onSurfaceVariant = Color(0xFFB8BED8),
            outline = Color(0xFF3A4260)
        )
        dark -> darkColorScheme(
            primary = Color(0xFF8B7CFF), onPrimary = Color(0xFF100D2B),
            primaryContainer = Color(0xFF342B78), onPrimaryContainer = Color(0xFFE7E3FF),
            secondary = Color(0xFFB18CFF), onSecondary = Color(0xFF21113D),
            secondaryContainer = Color(0xFF38265A), onSecondaryContainer = Color(0xFFEADFFF),
            tertiary = Color(0xFF55D8E8), onTertiary = Color(0xFF002F35),
            background = Color(0xFF090B18), surface = Color(0xFF14182A),
            surfaceVariant = Color(0xFF20263B), onSurface = Color(0xFFE9EBFF),
            onSurfaceVariant = Color(0xFFB8BED8), outline = Color(0xFF3A4260)
        )
        else -> lightColorScheme(
            primary = Color(0xFF5546B8), onPrimary = Color.White,
            primaryContainer = Color(0xFFE8E2FF), onPrimaryContainer = Color(0xFF20145C),
            secondary = Color(0xFF65518C), tertiary = Color(0xFF006874),
            background = Color(0xFFF7F6FC), surface = Color.White,
            surfaceVariant = Color(0xFFEDEBF5), onSurface = Color(0xFF191827),
            onSurfaceVariant = Color(0xFF55546A), outline = Color(0xFF77758A)
        )
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun OpenCloneHome(themeMode: OpenCloneThemeMode, onThemeModeChange: (OpenCloneThemeMode) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listSearchFocusRequester = remember { FocusRequester() }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var externalSource by remember { mutableStateOf<ExternalApkSet?>(null) }
    var inspection by remember { mutableStateOf<ApkInspection?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var installInProgress by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var showClones by remember { mutableStateOf(false) }
    var clones by remember { mutableStateOf<List<InstalledClone>>(emptyList()) }
    var buildHistory by remember { mutableStateOf(CloneBuildHistoryRepository(context).getAll()) }
    var selectedClone by remember { mutableStateOf<InstalledClone?>(null) }
    var cloneSelectionMode by remember { mutableStateOf(false) }
    var cloneSortByName by remember { mutableStateOf(true) }
    var selectedClonePackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkUninstallQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var bulkUninstallIndex by remember { mutableStateOf(0) }
    var bulkUninstallRequested by remember { mutableStateOf(false) }

    var cloneSource by remember { mutableStateOf<InstalledApp?>(null) }
    var cloneName by remember { mutableStateOf("") }
    var clonePackage by remember { mutableStateOf("") }
    var cloneBuilding by remember { mutableStateOf(false) }
    var cloneWorkId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloneWorkInfo by remember { mutableStateOf<WorkInfo?>(null) }
    var cloneProgress by remember { mutableStateOf(0) }
    var markCloneIcon by remember { mutableStateOf(true) }
    var rewriteDexPackageStrings by remember { mutableStateOf(false) }
    var customIconPath by rememberSaveable { mutableStateOf<String?>(null) }
    var badgePosition by remember { mutableStateOf(BadgePosition.BOTTOM_RIGHT) }
    var requestedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var removedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var versionNameOverride by remember { mutableStateOf("") }
    var versionCodeOverride by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf<String?>(null) }
    var cloneUpdateMode by remember { mutableStateOf(false) }
    var cloneResult by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingInstall by remember { mutableStateOf<CloneBuildResult?>(null) }
    var pendingExport by remember { mutableStateOf<CloneBuildResult?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf("Home") }
    var showSigningKeys by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var signingAliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeSigningAlias by remember { mutableStateOf<String?>(null) }
    var signingAliasInput by remember { mutableStateOf("") }
    var signingPassword by remember { mutableStateOf("") }
    var pendingSigningExportAlias by remember { mutableStateOf<String?>(null) }
    var signingMessage by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf(CloneProfileRepository(context).getAll()) }
    var showProfiles by remember { mutableStateOf(false) }
    var profileNameInput by remember { mutableStateOf("") }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var profileMessage by remember { mutableStateOf<String?>(null) }

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
        installInProgress = true
        installMessage = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CloneInstaller.install(context, result) }
            }
            outcome.onFailure {
                installInProgress = false
                installMessage = it.message ?: "Could not start the Android installer."
            }
            statusMessage = outcome.fold(
                onSuccess = { "Installation started. Complete the Android system confirmation." },
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
                        requestedPermissions = source.apkFiles.firstOrNull()?.let { readArchivePermissions(context, it) }.orEmpty()
                        removedPermissions = emptySet()
                        cloneName = "${source.label} Clone".take(100)
                        clonePackage = suggestedClonePackage(source.packageName, context.packageManager)
                        versionNameOverride = ""
                        versionCodeOverride = ""
                        cloneError = null
                        cloneUpdateMode = false
                        markCloneIcon = true
                        rewriteDexPackageStrings = false
                        customIconPath = null
                        badgePosition = BadgePosition.BOTTOM_RIGHT
                        statusMessage = "Imported ${source.apkFiles.size} APK component(s) and ${source.obbFiles.size} OBB file(s) from ${source.archiveName}."
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
                                result.obbFiles.forEach { file ->
                                    zip.putNextEntry(ZipEntry("Android/obb/${result.clonePackage}/${file.name}"))
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
                val message = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    if (operation == "uninstall") "Clone uninstalled: $packageName" else "Clone installed successfully: $packageName"
                } else {
                    "${if (operation == "uninstall") "Uninstall" else "Installation"} failed for $packageName${if (detail.isBlank()) "" else ": $detail"}"
                }
                statusMessage = message
                if (operation == "install") {
                    installInProgress = false
                    installMessage = message
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
        val versionCode = versionCodeOverride.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (versionCodeOverride.isNotBlank() && (versionCode == null || versionCode !in 1..Int.MAX_VALUE.toLong())) {
            cloneError = "Version code must be a positive whole number no greater than ${Int.MAX_VALUE}."
            return
        }
        if (versionNameOverride.length > 100) {
            cloneError = "Version name must be 100 characters or fewer."
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
            .putBoolean(CloneBuildWorker.KEY_REWRITE_DEX_PACKAGE_STRINGS, rewriteDexPackageStrings)
            .putStringArray(CloneBuildWorker.KEY_REMOVED_PERMISSIONS, removedPermissions.toTypedArray())
            .putString(CloneBuildWorker.KEY_VERSION_NAME_OVERRIDE, versionNameOverride.trim().takeIf { it.isNotEmpty() })
            .putInt(CloneBuildWorker.KEY_VERSION_CODE_OVERRIDE, versionCode?.toInt() ?: 0)
            .putBoolean(CloneBuildWorker.KEY_ALLOW_UPDATE, cloneUpdateMode)
            .putString(CloneBuildWorker.KEY_ICON_PACKAGE, installedSource?.packageName)
            .putString(CloneBuildWorker.KEY_CUSTOM_ICON_PATH, customIconPath.takeIf { installedSource != null })
            .putStringArray(CloneBuildWorker.KEY_OBB_FILES, importedSource?.obbFiles?.map { it.absolutePath }?.toTypedArray() ?: emptyArray())
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
                                    val completedBuild = CloneBuildResult(
                                        originalPackage = info.outputData.getString(CloneBuildWorker.KEY_ORIGINAL_PACKAGE).orEmpty(),
                                        clonePackage = info.outputData.getString(CloneBuildWorker.KEY_CLONE_PACKAGE).orEmpty(),
                                        cloneLabel = info.outputData.getString(CloneBuildWorker.KEY_CLONE_LABEL).orEmpty(),
                                        apks = outputFiles,
                                        iconBadgeApplied = info.outputData.getBoolean(CloneBuildWorker.KEY_ICON_BADGE_APPLIED, false),
                                        customIconApplied = info.outputData.getBoolean(CloneBuildWorker.KEY_CUSTOM_ICON_APPLIED, false),
                                        obbFiles = info.outputData.getStringArray(CloneBuildWorker.KEY_OUTPUT_OBB_FILES)?.map(::File).orEmpty()
                                    )
                                    cloneResult = completedBuild
                                    scope.launch {
                                        val savedHistory = withContext(Dispatchers.IO) {
                                            runCatching { CloneBuildHistoryRepository(context).add(completedBuild) }
                                        }
                                        savedHistory.onSuccess { buildHistory = it }
                                            .onFailure { statusMessage = it.message ?: "Could not save build history." }
                                    }
                                    cloneSource = null
                                    externalSource = null
                                    statusMessage = if (cloneResult?.obbFiles?.isNotEmpty() == true) "Clone APK set built and signature-verified. OBB expansion data is included in ZIP export." else "Clone APK set built and signature-verified."
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
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("OPENCLONE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(currentTab, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (currentTab == "Home") {
                    TextButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("⚙️  Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                } else if (currentTab in setOf("Apps", "Cloned apps")) {
                    TextButton(
                        onClick = { runCatching { listSearchFocusRequester.requestFocus() } },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("⌕  Search", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            when (currentTab) {
                "Home" -> LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(Brush.linearGradient(listOf(Color(0xFF174A83), Color(0xFF342B78), Color(0xFF17677A))), RoundedCornerShape(22.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Clone apps. Your way.", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
                            Text("Create independent app spaces with custom identity and signing.", color = Color(0xFFE1E8FF))
                            Text("${apps.size} apps ready  •  ${clones.size} installed clones", color = Color(0xFF9DECF5), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    item { Text("App Cloner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HomeActionTile("Apps", "Clone installed apps", Modifier.weight(1f)) { currentTab = "Apps" }
                                HomeActionTile("APKs", "Clone APK files", Modifier.weight(1f)) { currentTab = "APKs" }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HomeActionTile("Cloned apps", "Manage installed clones", Modifier.weight(1f)) { currentTab = "Cloned apps" }
                                HomeActionTile("Cloned APKs", "Manage generated APKs", Modifier.weight(1f)) { currentTab = "Cloned APKs" }
                            }
                        }
                    }
                    item {
                        Text("Advanced tools such as isolated WebView, per-clone VPN/proxy, tunnel manager, remote control and MCP server are roadmap items; they are not enabled yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "Apps" -> Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Installed applications", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("System apps", style = MaterialTheme.typography.labelSmall)
                            Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
                        }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(listSearchFocusRequester), singleLine = true, label = { Text("Search apps or package ID") })
                    when {
                        loading -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Text("Loading apps…") }
                        visibleApps.isEmpty() -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("No matching apps found.") }
                        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visibleApps, key = { it.packageName }) { app -> InstalledAppRow(app = app, onClick = { selectedApp = app }) }
                        }
                    }
                }
                "Cloned apps" -> Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    val filteredClones = clones.filter {
                        it.cloneLabel.contains(query, true) || it.clonePackage.contains(query, true) ||
                            it.originalPackage.contains(query, true)
                    }.let { matches ->
                        if (cloneSortByName) matches.sortedBy { it.cloneLabel.lowercase() }
                        else matches.sortedByDescending { it.updateAvailable }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${clones.size} installed · ${selectedClonePackages.size} selected", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { cloneSortByName = !cloneSortByName }) {
                                Text(if (cloneSortByName) "Sort: name" else "Sort: updates")
                            }
                            TextButton(onClick = {
                                cloneSelectionMode = !cloneSelectionMode
                                if (!cloneSelectionMode) selectedClonePackages = emptySet()
                            }) { Text(if (cloneSelectionMode) "Done" else "Select") }
                        }
                    }
                    if (cloneSelectionMode) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val visiblePackages = filteredClones.map { it.clonePackage }.toSet()
                                selectedClonePackages = if (visiblePackages.all { it in selectedClonePackages })
                                    selectedClonePackages - visiblePackages else selectedClonePackages + visiblePackages
                            }) { Text("Select all") }
                            TextButton(enabled = selectedClonePackages.isNotEmpty(), onClick = {
                                bulkUninstallQueue = clones.map { it.clonePackage }.filter { it in selectedClonePackages }
                                bulkUninstallIndex = 0
                                bulkUninstallRequested = false
                            }) { Text("Uninstall selected") }
                            TextButton(onClick = { selectedClonePackages = emptySet() }) { Text("Clear") }
                        }
                        Text("Each app requires its own Android system confirmation. OpenClone will not remove apps silently.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(listSearchFocusRequester),
                        singleLine = true, label = { Text("Search clone name or package ID") })
                    if (filteredClones.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("🧬", style = MaterialTheme.typography.displayMedium)
                            Text(if (clones.isEmpty()) "Installed clones will appear here" else "No clones match this search")
                        }
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredClones, key = { it.clonePackage }) { clone ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cloneSelectionMode) Checkbox(
                                    checked = clone.clonePackage in selectedClonePackages,
                                    onCheckedChange = { checked ->
                                        selectedClonePackages = if (checked) selectedClonePackages + clone.clonePackage
                                            else selectedClonePackages - clone.clonePackage
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    InstalledAppRow(app = clone.app, onClick = {
                                        if (cloneSelectionMode) selectedClonePackages = if (clone.clonePackage in selectedClonePackages)
                                            selectedClonePackages - clone.clonePackage else selectedClonePackages + clone.clonePackage
                                        else selectedClone = clone
                                    }, subtitle = "${clone.updateStatus} · ${clone.clonePackage}")
                                }
                            }
                        }
                    }
                }
                "APKs" -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("▤", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Add APK files to clone here", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { importBundle.launch(arrayOf("*/*")) }) { Text("＋ Import APK / XAPK / APKM") }
                    inspection?.let { info ->
                        Text("Selected: ${info.label}", modifier = Modifier.padding(top = 12.dp))
                    }
                }
                "Cloned APKs" -> Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Saved builds · ${buildHistory.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { buildHistory = CloneBuildHistoryRepository(context).getAll() }) { Text("Refresh") }
                    }
                    if (buildHistory.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("⇩", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                            Text("No generated APK builds yet", style = MaterialTheme.typography.titleMedium)
                            Text("Completed builds will be saved here for install or export.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(buildHistory, key = { "${it.clonePackage}-${it.createdAtMillis}" }) { entry ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.cloneLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(entry.clonePackage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAtMillis)) + " · ${entry.apkPaths.size} APK(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!entry.filesAvailable) Text("Build files are no longer available. Rebuild this clone to restore them.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(enabled = entry.filesAvailable, onClick = { installClone(entry.toBuildResult()) }) { Text("Install") }
                                        TextButton(enabled = entry.filesAvailable, onClick = { pendingExport = entry.toBuildResult(); exportClone.launch("${entry.clonePackage}-clone-apk-set.zip") }) { Text("Export") }
                                        TextButton(onClick = { buildHistory = CloneBuildHistoryRepository(context).remove(entry.clonePackage, entry.createdAtMillis) }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Extensions Store", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Optional tools that add capabilities without bloating the core app. The catalogue preview is available now; secure extension installation is still under development.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        ExtensionPreviewCard("🎨", "Icon Studio", "Advanced launcher icon editing and adaptive icon previews", "Planned")
                    }
                    item {
                        ExtensionPreviewCard("🧩", "Clone Profiles", "Save reusable clone configurations and apply them to future builds", "Planned")
                    }
                    item {
                        ExtensionPreviewCard("🛡️", "Backup Tools", "Export and restore supported OpenClone settings and profiles", "Planned")
                    }
                    item {
                        ExtensionPreviewCard("🌐", "Network Profiles", "Manage supported per-clone network configuration", "Research")
                    }
                    item {
                        ExtensionPreviewCard("🧰", "Developer Diagnostics", "Inspect build metadata, compatibility warnings and logs", "Planned")
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Secure extension platform", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Before downloads are enabled, OpenClone needs a signed extension format, publisher verification, explicit capability permissions, compatibility checks, and safe install/update/removal flows. Extensions will not receive unrestricted access to cloned apps or private files.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            statusMessage?.let { message -> TextButton(onClick = { statusMessage = null }, modifier = Modifier.fillMaxWidth()) { Text(message) } }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("Home", "Apps", "APKs", "Cloned apps", "Cloned APKs", "Store").forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab; query = "" },
                            icon = {
                                val (glyph, tint) = when (tab) {
                                    "Home" -> "🏠" to Color(0xFF42C8FF)
                                    "Apps" -> "🟩" to Color(0xFF42D7A5)
                                    "APKs" -> "📦" to Color(0xFFFFA64D)
                                    "Cloned apps" -> "🧬" to Color(0xFFB56BFF)
                                    "Cloned APKs" -> "📥" to Color(0xFF55D88A)
                                    else -> "🛍️" to Color(0xFFFF79C6)
                                }
                                Text(glyph, style = MaterialTheme.typography.headlineSmall)
                            },
                            label = { Text(tab, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            alwaysShowLabel = true,
                            modifier = Modifier.widthIn(min = 76.dp)
                        )
                    }
                }
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
                    requestedPermissions = readInstalledPermissions(context, app.packageName)
                    removedPermissions = emptySet()
                    cloneName = "${app.label} Clone".take(100)
                    clonePackage = suggestedClonePackage(app.packageName, context.packageManager)
                    versionNameOverride = ""
                    versionCodeOverride = ""
                    cloneError = null
                    markCloneIcon = true
                    rewriteDexPackageStrings = false
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
            title = { Text(if (cloneUpdateMode) "Update clone" else "Create clone") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Original: $sourcePackage · $sourceLabel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (cloneUpdateMode) "OpenClone will rebuild this clone using its existing package ID and signing identity." else "OpenClone will create a separate copy using the recommended app name and package ID. Default clone settings will be applied automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it; cloneError = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Clone app name") }
                    )
                    OutlinedTextField(
                        value = clonePackage,
                        onValueChange = { clonePackage = it; cloneError = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Clone package ID") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = markCloneIcon, onCheckedChange = { markCloneIcon = it })
                        Text("Add clone badge to icon")
                    }
                    TextButton(onClick = {
                        profiles = CloneProfileRepository(context).getAll()
                        profileMessage = null
                        profileNameInput = ""
                        editingProfileId = null
                        showProfiles = true
                    }) { Text("Apply saved profile") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = profileNameInput,
                            onValueChange = { profileNameInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Profile name") }
                        )
                        TextButton(onClick = {
                            val sourcePackage = cloneSource?.packageName ?: externalSource?.packageName.orEmpty()
                            if (sourcePackage.isBlank()) {
                                cloneError = "Choose an original app before saving a profile."
                            } else {
                                runCatching {
                                    CloneProfileRepository(context).save(
                                        name = profileNameInput,
                                        originalPackage = sourcePackage,
                                        clonePackage = clonePackage.trim(),
                                        cloneLabel = cloneName.trim(),
                                        markIcon = markCloneIcon,
                                        badgePosition = badgePosition.name,
                                        existingId = editingProfileId
                                    )
                                }.onSuccess {
                                    profiles = CloneProfileRepository(context).getAll()
                                    profileNameInput = ""
                                    editingProfileId = null
                                    cloneError = null
                                    statusMessage = "Clone profile saved."
                                }.onFailure {
                                    cloneError = it.message ?: "Could not save profile."
                                }
                            }
                        }, enabled = profileNameInput.isNotBlank()) {
                            Text(if (editingProfileId == null) "Save" else "Update")
                        }
                    }
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
                    Text(
                        if (cloneUpdateMode) "The update uses the existing OpenClone signing key. Android should preserve this clone's data if the update is accepted." else "The original app remains unchanged. The clone uses separate app data and is signed with your OpenClone signing identity.",
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

    if (showProfiles) {
        AlertDialog(
            onDismissRequest = { showProfiles = false },
            title = { Text("Clone Profiles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Profiles save reusable names, package IDs and icon-badge preferences only. APK files, signing keys, credentials and app data are never included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (profiles.isEmpty()) {
                        Text("No saved profiles yet. Configure this clone and save a profile.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(profiles, key = { it.id }) { profile ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                                        Text(profile.originalPackage, style = MaterialTheme.typography.labelSmall)
                                        Text("${profile.cloneLabel} · ${profile.clonePackage}", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    val source = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            InstalledAppRepository(context.packageManager)
                                                                .getInstalledApps(includeSystemApps = true)
                                                                .firstOrNull { it.packageName == profile.originalPackage }
                                                        }.getOrNull()
                                                    }
                                                    if (source == null) {
                                                        profileMessage = "Install the original app (${profile.originalPackage}) before applying this profile."
                                                    } else {
                                                        cloneSource = source
                                                        externalSource = null
                                                        requestedPermissions = readInstalledPermissions(context, source.packageName)
                                                        removedPermissions = emptySet()
                                                        cloneName = profile.cloneLabel
                                                        clonePackage = profile.clonePackage
                                                        markCloneIcon = profile.markIcon
                                                        badgePosition = runCatching { BadgePosition.valueOf(profile.badgePosition) }.getOrDefault(BadgePosition.BOTTOM_RIGHT)
                                                        customIconPath = null
                                                        cloneUpdateMode = false
                                                        cloneError = null
                                                        showProfiles = false
                                                        statusMessage = "Profile applied. Review the settings before building."
                                                    }
                                                }
                                            }) { Text("Apply") }
                                            TextButton(onClick = {
                                                profileNameInput = profile.name
                                                editingProfileId = profile.id
                                                showProfiles = false
                                            }) { Text("Rename") }
                                            TextButton(onClick = {
                                                runCatching {
                                                    CloneProfileRepository(context).save(
                                                        name = "${profile.name} copy".take(60),
                                                        originalPackage = profile.originalPackage,
                                                        clonePackage = profile.clonePackage,
                                                        cloneLabel = profile.cloneLabel,
                                                        markIcon = profile.markIcon,
                                                        badgePosition = profile.badgePosition
                                                    )
                                                }.onSuccess {
                                                    profiles = CloneProfileRepository(context).getAll()
                                                    profileMessage = "Profile duplicated."
                                                }.onFailure {
                                                    profileMessage = it.message ?: "Could not duplicate profile."
                                                }
                                            }) { Text("Duplicate") }
                                            TextButton(onClick = {
                                                runCatching { CloneProfileRepository(context).delete(profile.id) }
                                                    .onSuccess {
                                                        profiles = CloneProfileRepository(context).getAll()
                                                        profileMessage = if (it) "Profile deleted." else "Profile was already removed."
                                                    }
                                                    .onFailure { profileMessage = it.message ?: "Could not delete profile." }
                                            }) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    profileMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProfiles = false }) { Text("Close") } }
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

    if (installInProgress) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Installing clone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Preparing the APK package and waiting for Android's installer confirmation.")
                    Text("Approve the installation in the Android system dialog to finish.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { }) { Text("Waiting…") } }
        )
    }

    installMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { installMessage = null },
            title = { Text(if (message.startsWith("Clone installed successfully")) "Installation complete" else "Installation result") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { installMessage = null }) { Text("OK") } }
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

    if (bulkUninstallQueue.isNotEmpty() && bulkUninstallIndex < bulkUninstallQueue.size) {
        val packageName = bulkUninstallQueue[bulkUninstallIndex]
        AlertDialog(
            onDismissRequest = { bulkUninstallQueue = emptyList(); bulkUninstallIndex = 0; bulkUninstallRequested = false },
            title = { Text("Bulk uninstall · ${bulkUninstallIndex + 1} of ${bulkUninstallQueue.size}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(packageName, fontWeight = FontWeight.SemiBold)
                    Text(if (bulkUninstallRequested)
                        "Complete or cancel the Android system prompt, return here, then continue to the next selected clone."
                    else "Open Android's system uninstall confirmation. You must confirm removal in Android.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!bulkUninstallRequested) {
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { CloneInstaller.uninstall(context, packageName) }
                            }
                            statusMessage = outcome.fold(
                                onSuccess = { "Android uninstall prompt opened for $packageName. Confirm or cancel it, then continue here." },
                                onFailure = { it.message ?: "Could not start uninstall for $packageName." }
                            )
                            bulkUninstallRequested = outcome.isSuccess
                        }
                    } else {
                        bulkUninstallIndex += 1
                        bulkUninstallRequested = false
                        if (bulkUninstallIndex >= bulkUninstallQueue.size) {
                            bulkUninstallQueue = emptyList()
                            bulkUninstallIndex = 0
                            selectedClonePackages = emptySet()
                            cloneSelectionMode = false
                            refreshApps()
                            statusMessage = "Uninstall requests processed. Inventory refreshed; Android confirmations determine which apps were removed."
                        }
                    }
                }) { Text(if (bulkUninstallRequested) "Continue to next" else "Open system uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { bulkUninstallQueue = emptyList(); bulkUninstallIndex = 0; bulkUninstallRequested = false }) { Text("Stop") }
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
                    val installedApkBytes = (listOf(clone.app.applicationInfo.sourceDir) +
                        clone.app.applicationInfo.splitSourceDirs.orEmpty()).distinct().sumOf { path ->
                        runCatching { File(path).length() }.getOrDefault(0L)
                    }
                    DetailLine(
                        "Installed APK footprint",
                        if (installedApkBytes >= 1024L * 1024L)
                            "${"%.1f".format(installedApkBytes / (1024.0 * 1024.0))} MB"
                        else "${installedApkBytes / 1024L} KB"
                    )
                    Text(
                        "This is the installed APK footprint only. Android does not expose another app's private data/cache size to ordinary apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                requestedPermissions = readInstalledPermissions(context, original.packageName)
                                removedPermissions = emptySet()
                                cloneName = clone.cloneLabel
                                clonePackage = clone.clonePackage
                                versionNameOverride = ""
                                versionCodeOverride = ""
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

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Manage OpenClone preferences and information.")
                    Text("Appearance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Choose how OpenClone looks. Changes apply immediately and are saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OpenCloneThemeMode.values().forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onThemeModeChange(option) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == option, onClick = { onThemeModeChange(option) })
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(option.label, fontWeight = FontWeight.Medium)
                                if (option == OpenCloneThemeMode.AMOLED) {
                                    Text("True-black surfaces for OLED displays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Account & Tools", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showSettings = false
                            currentTab = "Store"
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Extensions", fontWeight = FontWeight.SemiBold)
                            Text("Add-ons and integrations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            statusMessage = "Backup and profile tools are not available yet."
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Backup / Profile", fontWeight = FontWeight.SemiBold)
                            Text("Local presets and restore · coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            val keys = SigningKeys.get(context)
                            signingAliases = keys.identityAliases
                            activeSigningAlias = keys.activeAlias
                            showSettings = false
                            showSigningKeys = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Signing identities", fontWeight = FontWeight.SemiBold)
                            Text("Manage keys used to sign cloned apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showSettings = false
                            showAbout = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("About & licenses", fontWeight = FontWeight.SemiBold)
                            Text("OpenClone version, GPL license and third-party notices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Close") }
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
                    Text("Compatibility preflight", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (apk.compatibilityReport.findings.isEmpty()) {
                        Text(
                            "No common Firebase or Google services indicators were found in the declared metadata. This is not a guarantee that the app will work after cloning.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        apk.compatibilityReport.findings.forEach { finding ->
                            Text("• $finding", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    apk.compatibilityReport.limitations.forEach { limitation ->
                        Text("• $limitation", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "This report is a best-effort check of declared permissions and metadata. It cannot detect every runtime, signature, or server-side dependency.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { inspection = null }) { Text("Done") } }
        )
    }
}

@Suppress("DEPRECATION")
private fun readInstalledPermissions(context: Context, packageName: String): List<String> {
    val flags = android.content.pm.PackageManager.GET_PERMISSIONS
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        context.packageManager.getPackageInfo(packageName, flags)
    }
    return info.requestedPermissions?.toList().orEmpty().distinct().sorted()
}

@Suppress("DEPRECATION")
private fun readArchivePermissions(context: Context, apk: File): List<String> {
    val flags = android.content.pm.PackageManager.GET_PERMISSIONS
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    } ?: return emptyList()
    return info.requestedPermissions?.toList().orEmpty().distinct().sorted()
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
private fun HomeActionTile(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

@Composable
private fun ExtensionPreviewCard(icon: String, title: String, description: String, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
