package org.openclone.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class IrdbEntry(val name: String, val path: String, val directory: Boolean)

/**
 * Read-only client for the public flipperdevices IRDB. All URLs are constructed
 * from a fixed HTTPS host/repository and validated path segments; downloaded
 * text is parsed by FlipperIrFileParser before it can be saved or transmitted.
 */
internal object CommunityIrdbRepository {
    private const val API_ROOT = "https://api.github.com/repos/flipperdevices/IRDB/contents/database/categories"
    private const val RAW_ROOT = "https://raw.githubusercontent.com/flipperdevices/IRDB/dev/database/categories"
    private const val MAX_LIST_BYTES = 256 * 1024
    private const val MAX_FILE_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 300

    fun list(path: String = ""): List<IrdbEntry> {
        val safePath = path.split('/').filter(String::isNotBlank).onEach { segment ->
            require(segment.matches(Regex("[A-Za-z0-9._ -]{1,100}")) && segment != "." && segment != "..") {
                "The catalogue returned an invalid path."
            }
        }.joinToString("/")
        val url = if (safePath.isEmpty()) "$API_ROOT?ref=dev"
            else "$API_ROOT/${safePath.split('/').joinToString("/") { encode(it) }}?ref=dev"
        val bytes = request(url, MAX_LIST_BYTES)
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= MAX_ENTRIES) { "The catalogue directory is larger than the supported limit." }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val name = item.optString("name")
                val type = item.optString("type")
                if (name.isBlank() || name.startsWith(".")) continue
                if (type != "dir" && !(type == "file" && name.endsWith(".ir", true))) continue
                val itemPath = item.optString("path")
                require(itemPath.startsWith("database/categories/") && !itemPath.contains("/../")) {
                    "The catalogue returned an unexpected path."
                }
                add(IrdbEntry(name, itemPath.removePrefix("database/categories/"), type == "dir"))
            }
        }
    }

    fun download(path: String): String {
        val segments = path.split('/')
        require(segments.size in 2..5 && segments.all {
            it.matches(Regex("[A-Za-z0-9._ -]{1,100}")) && it != "." && it != ".."
        }) { "Invalid community profile path." }
        require(path.endsWith(".ir", true)) { "Only Flipper .ir profiles can be imported." }
        val url = "$RAW_ROOT/${segments.joinToString("/") { encode(it) }}"
        return request(url, MAX_FILE_BYTES).toString(Charsets.UTF_8)
    }

    private fun request(address: String, maxBytes: Int): ByteArray {
        val url = URL(address)
        require(url.protocol == "https" && url.host in setOf("api.github.com", "raw.githubusercontent.com")) {
            "The remote profile host is not allowed."
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OpenClone-UniversalRemote")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) {
                if (status == 404) "This community profile is no longer available."
                else if (status == 403 || status == 429) "The community catalogue rate limit was reached. Try again later."
                else "Catalogue request failed (HTTP $status)."
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "The community catalogue response exceeded its size limit." }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
}

@Composable
fun CommunityIrRemoteCatalogueCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf(emptyList<IrdbEntry>()) }
    var brands by remember { mutableStateOf(emptyList<IrdbEntry>()) }
    var models by remember { mutableStateOf(emptyList<IrdbEntry>()) }
    var modelFiles by remember { mutableStateOf(emptyList<IrdbEntry>()) }
    var selectedCategory by remember { mutableStateOf<IrdbEntry?>(null) }
    var selectedBrand by remember { mutableStateOf<IrdbEntry?>(null) }
    var selectedModel by remember { mutableStateOf<IrdbEntry?>(null) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Browse community-submitted infrared profiles by appliance, brand and model.") }

    fun loadDirectory(path: String, onLoaded: (List<IrdbEntry>) -> Unit) {
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CommunityIrdbRepository.list(path) } }
                .onSuccess(onLoaded)
                .onFailure { message = it.message ?: "Could not load the community catalogue." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadDirectory("") { categories = it } }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Community IR catalogue", style = MaterialTheme.typography.titleMedium)
            Text(
                "Browse Flipper-IRDB community profiles. These are not manufacturer-certified; confirm the exact model and test each command. Downloaded profiles are saved for offline use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter visible categories, brands or models") }
            )
            if (loading) CircularProgressIndicator()
            Text("1 · Appliance category", style = MaterialTheme.typography.labelLarge)
            categories.filter { it.directory && (it.name.contains(search, true) || search.isBlank()) }.forEach { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.name.replace('_', ' '), modifier = Modifier.weight(1f))
                    Button(onClick = {
                        selectedCategory = entry
                        selectedBrand = null
                        selectedModel = null
                        brands = emptyList()
                        models = emptyList()
                        modelFiles = emptyList()
                        loadDirectory(entry.path) { brands = it.filter(IrdbEntry::directory) }
                    }) { Text(if (selectedCategory?.path == entry.path) "Selected" else "Browse") }
                }
            }
            selectedCategory?.let { category ->
                Text("2 · Brand", style = MaterialTheme.typography.labelLarge)
                brands.filter { it.name.contains(search, true) || search.isBlank() }.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.name, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            selectedBrand = entry
                            selectedModel = null
                            modelFiles = emptyList()
                            loadDirectory(entry.path) { models = it }
                        }) { Text(if (selectedBrand?.path == entry.path) "Selected" else "Browse") }
                    }
                }
                selectedBrand?.let {
                    Text("3 · Model / profile", style = MaterialTheme.typography.labelLarge)
                    models.filter { entry -> entry.name.contains(search, true) || search.isBlank() }.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.name, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                if (entry.directory) {
                                    selectedModel = entry
                                    loadDirectory(entry.path) { modelFiles = it.filter { file -> !file.directory && file.name.endsWith(".ir", true) } }
                                } else {
                                    selectedModel = entry
                                    modelFiles = listOf(entry)
                                }
                            }) { Text("Open") }
                        }
                    }
                }
                selectedModel?.let { model ->
                    Text("4 · Import profile", style = MaterialTheme.typography.labelLarge)
                    modelFiles.forEach { file ->
                        Button(
                            enabled = !loading,
                            onClick = {
                                loading = true
                                scope.launch {
                                    runCatching {
                                        val commands = withContext(Dispatchers.IO) {
                                            FlipperIrFileParser.parse(CommunityIrdbRepository.download(file.path))
                                        }
                                        val category = when {
                                            category.name.contains("tv", true) -> ApplianceCategory.TELEVISION
                                            category.name.contains("ac", true) || category.name.contains("air_condition", true) -> ApplianceCategory.AIR_CONDITIONER
                                            category.name.contains("fan", true) -> ApplianceCategory.FAN
                                            category.name.contains("projector", true) -> ApplianceCategory.PROJECTOR
                                            category.name.contains("audio", true) || category.name.contains("sound", true) -> ApplianceCategory.AUDIO
                                            category.name.contains("purifier", true) -> ApplianceCategory.AIR_PURIFIER
                                            else -> ApplianceCategory.OTHER
                                        }
                                        val profile = InfraredProfileCodec.newProfile(
                                            name = "${selectedBrand?.name.orEmpty()} ${model.name.removeSuffix(".ir")}".take(80),
                                            brand = selectedBrand?.name.orEmpty(),
                                            model = model.name.removeSuffix(".ir").take(100),
                                            category = category,
                                            commands = commands
                                        )
                                        InfraredProfileStore.save(context, profile)
                                        commands.size
                                    }.onSuccess { count ->
                                        message = "Saved $count validated commands from ${file.name}. Community supplied · not manufacturer verified."
                                    }.onFailure { error ->
                                        message = error.message ?: "Could not import this community profile."
                                    }
                                    loading = false
                                }
                            }
                        ) { Text("Download ${file.name}") }
                    }
                }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Text(
                "Source: flipperdevices/IRDB (MIT). The database is community maintained; profile presence does not prove compatibility with a particular appliance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
