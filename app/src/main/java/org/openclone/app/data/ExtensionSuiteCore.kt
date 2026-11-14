package org.openclone.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

/** Persisted, bounded audit trail for permission-manifest decisions made during clone builds. */
data class PrivacyAuditEntry(
    val originalPackage: String,
    val clonePackage: String,
    val requestedPermissions: List<String>,
    val removedPermissions: List<String>,
    val recordedAtMillis: Long
)

class PrivacyAuditRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openclone_privacy_audit", Context.MODE_PRIVATE)

    @Synchronized
    fun record(
        originalPackage: String,
        clonePackage: String,
        requestedPermissions: Collection<String>,
        removedPermissions: Collection<String>,
        atMillis: Long = System.currentTimeMillis()
    ): PrivacyAuditEntry {
        val (requested, removed) = validate(originalPackage, clonePackage, requestedPermissions, removedPermissions)
        val entry = PrivacyAuditEntry(originalPackage, clonePackage, requested, removed, atMillis)
        val updated = (listOf(entry) + getAll()).take(MAX_ENTRIES)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().apply {
                put("originalPackage", item.originalPackage)
                put("clonePackage", item.clonePackage)
                put("requested", JSONArray(item.requestedPermissions))
                put("removed", JSONArray(item.removedPermissions))
                put("recordedAtMillis", item.recordedAtMillis)
            })
        }
        check(prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()) { "Could not save the privacy audit." }
        return entry
    }

    companion object {
        fun validate(
            originalPackage: String,
            clonePackage: String,
            requestedPermissions: Collection<String>,
            removedPermissions: Collection<String>
        ): Pair<List<String>, List<String>> {
            require(PACKAGE_PATTERN.matches(originalPackage)) { "Invalid original package ID." }
            require(PACKAGE_PATTERN.matches(clonePackage)) { "Invalid clone package ID." }
            val requested = requestedPermissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            val removed = removedPermissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            require(requested.size <= MAX_PERMISSIONS && removed.size <= MAX_PERMISSIONS) { "Permission audit exceeds the supported limit." }
            require(removed.all { it in requested }) { "Removed permissions must be present in the requested manifest list." }
            return requested to removed
        }
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
        private const val MAX_PERMISSIONS = 256
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }

    @Synchronized
    fun getAll(): List<PrivacyAuditEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    PrivacyAuditEntry(
                        originalPackage = item.getString("originalPackage"),
                        clonePackage = item.getString("clonePackage"),
                        requestedPermissions = item.optJSONArray("requested").toStringList(),
                        removedPermissions = item.optJSONArray("removed").toStringList(),
                        recordedAtMillis = item.optLong("recordedAtMillis")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()



data class StorageSnapshot(
    val cacheBytes: Long,
    val filesBytes: Long,
    val buildOutputBytes: Long,
    val buildOutputFiles: Int
) {
    companion object {
        fun read(context: Context): StorageSnapshot {
            val builds = File(context.filesDir, "openclone/builds")
            val buildFiles = builds.walkTopDown().filter(File::isFile).toList()
            return StorageSnapshot(
                cacheBytes = directoryBytes(context.cacheDir),
                filesBytes = directoryBytes(context.filesDir),
                buildOutputBytes = buildFiles.sumOf { it.length() },
                buildOutputFiles = buildFiles.size
            )
        }

        private fun directoryBytes(directory: File): Long =
            runCatching { directory.walkTopDown().filter(File::isFile).sumOf { it.length() } }.getOrDefault(0L)
    }
}

/** ZIP-based diagnostics only; does not claim to resolve the manifest's final resource-table reference. */
data class IconResourceReport(
    val launcherImageCount: Int,
    val launcherXmlCandidateCount: Int,
    val densityBuckets: List<String>,
    val warnings: List<String>
) {
    companion object {
        fun inspect(apk: File): IconResourceReport {
            require(apk.isFile && apk.canRead()) { "APK is not readable." }
            val names = java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            fun looksLikeLauncher(name: String): Boolean {
                val base = name.substringAfterLast('/').substringBeforeLast('.')
                return base.contains("launcher", true) || base.contains("ic_app", true) || base.contains("icon", true)
            }
            val launcherImages = names.count { name ->
                name.startsWith("res/") &&
                    (name.contains("mipmap") || name.contains("drawable")) &&
                    looksLikeLauncher(name) &&
                    name.substringAfterLast('.').lowercase() in setOf("png", "webp", "jpg", "jpeg")
            }
            val launcherXml = names.count { name ->
                name.startsWith("res/") && name.endsWith(".xml") && looksLikeLauncher(name)
            }
            val buckets = names.mapNotNull { name ->
                Regex("res/(mipmap[^/]*|drawable[^/]*)/").find(name)?.groupValues?.get(1)
            }.distinct().sorted()
            val warnings = buildList {
                if (launcherImages == 0) add("No obvious raster launcher icon was found by filename; the manifest resource may use another name.")
                if (launcherXml > 0) add("Compiled launcher XML resources were found. A resource-table-aware parser is needed to distinguish adaptive icons, vectors and layer lists reliably.")
                if (buckets.size > 1) add("Multiple density or resource-qualifier buckets are present; all variants must be checked when replacing an icon.")
            }
            return IconResourceReport(launcherImages, launcherXml, buckets, warnings)
        }
    }
}

/** Bounded event log. Callers should pass event codes and redacted summaries, never secrets or file paths. */
data class ExtensionLogEvent(val atMillis: Long, val area: String, val code: String, val summary: String)

class ExtensionDiagnosticsLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openclone_extension_diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun append(area: String, code: String, summary: String) {
        require(area.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        require(code.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        val safeSummary = summary.replace(Regex("(?i)(password|token|secret|authorization)\\s*[:=]\\s*[^ ,;]+"), "$1=[redacted]")
            .replace(Regex("(/data/user/[^ ,;]+|/data/data/[^ ,;]+)"), "[private-path]")
            .take(240)
        val updated = (getAll() + ExtensionLogEvent(System.currentTimeMillis(), area, code, safeSummary)).takeLast(MAX_EVENTS)
        val array = JSONArray()
        updated.forEach { event ->
            array.put(JSONObject().apply {
                put("atMillis", event.atMillis)
                put("area", event.area)
                put("code", event.code)
                put("summary", event.summary)
            })
        }
        check(prefs.edit().putString(KEY_EVENTS, array.toString()).commit()) { "Could not persist diagnostics event." }
    }

    @Synchronized
    fun getAll(): List<ExtensionLogEvent> = runCatching {
        val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(ExtensionLogEvent(item.optLong("atMillis"), item.optString("area"), item.optString("code"), item.optString("summary")))
            }
        }
    }.getOrDefault(emptyList())

    fun exportJson(): String {
        val events = JSONArray()
        getAll().forEach { event ->
            events.put(JSONObject().apply {
                put("atMillis", event.atMillis)
                put("area", event.area)
                put("code", event.code)
                put("summary", event.summary)
            })
        }
        return JSONObject().put("format", "openclone-redacted-runtime-diagnostics")
            .put("formatVersion", 1).put("events", events).toString(2)
    }

    private companion object {
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 200
    }
}

/** A signed package descriptor is verified before any package can be staged for installation. */
data class SignedExtensionDescriptor(
    val extensionId: String,
    val version: String,
    val publisher: String,
    val minimumOpenCloneApi: Int,
    val maximumOpenCloneApi: Int,
    val sha256: String,
    val signatureBase64: String
) {
    fun canonicalPayload(): ByteArray {
        require(extensionId.matches(Regex("[a-z][a-z0-9_.-]{2,63}")))
        require(version.matches(Regex("[0-9]+\\\\.[0-9]+\\\\.[0-9]+")))
        require(publisher.isNotBlank() && publisher.length <= 120)
        require(minimumOpenCloneApi > 0 && maximumOpenCloneApi >= minimumOpenCloneApi)
        require(sha256.matches(Regex("[A-Fa-f0-9]{64}")))
        return listOf(extensionId, version, publisher, minimumOpenCloneApi, maximumOpenCloneApi, sha256.lowercase()).joinToString("\\n").toByteArray(Charsets.UTF_8)
    }
}

object ExtensionPackageVerifier {
    fun verify(descriptor: SignedExtensionDescriptor, packageBytes: ByteArray, trustedPublisherKey: PublicKey, currentOpenCloneApi: Int = 1): Boolean {
        if (packageBytes.isEmpty() || packageBytes.size > MAX_PACKAGE_BYTES) return false
        if (currentOpenCloneApi !in descriptor.minimumOpenCloneApi..descriptor.maximumOpenCloneApi) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(packageBytes)
                .joinToString("") { "%02x".format(it) }
            if (!digest.equals(descriptor.sha256, ignoreCase = true)) return false
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(trustedPublisherKey)
            verifier.update(descriptor.canonicalPayload())
            verifier.verify(Base64.getDecoder().decode(descriptor.signatureBase64))
        }.getOrDefault(false)
    }

    private const val MAX_PACKAGE_BYTES = 100 * 1024 * 1024
}

/** HMAC authentication primitives for a future explicitly paired local-control transport. */
object RemoteControlAuthenticator {
    private val random = SecureRandom()

    fun createPairingSecret(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun sign(secret: ByteArray, method: String, path: String, timestampMillis: Long, nonce: String): String {
        require(secret.size >= 32 && method.matches(Regex("[A-Z]{3,10}")))
        require(path.startsWith("/") && path.length <= 512 && !path.contains("\n"))
        require(nonce.matches(Regex("[A-Za-z0-9_-]{16,80}")))
        val payload = "$method\\n$path\\n$timestampMillis\\n$nonce".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    fun verifyAndConsume(
        secret: ByteArray,
        method: String,
        path: String,
        timestampMillis: Long,
        nonce: String,
        signature: String,
        replayGuard: RemoteControlReplayGuard,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return verify(secret, method, path, timestampMillis, nonce, signature, nowMillis) &&
            replayGuard.consume(nonce, timestampMillis, nowMillis)
    }

    fun verify(
        secret: ByteArray,
        method: String,
        path: String,
        timestampMillis: Long,
        nonce: String,
        signature: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (kotlin.math.abs(nowMillis - timestampMillis) > TimeUnit.MINUTES.toMillis(1)) return false
        return runCatching {
            val expected = sign(secret, method, path, timestampMillis, nonce)
            MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))
        }.getOrDefault(false)
    }
}

class RemoteControlReplayGuard(private val maxEntries: Int = 256) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun consume(nonce: String, timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (maxEntries !in 1..4096 || kotlin.math.abs(nowMillis - timestampMillis) > TimeUnit.MINUTES.toMillis(1)) return false
        if (!nonce.matches(Regex("[A-Za-z0-9_-]{16,80}")) || nonce in seen) return false
        seen.add(nonce)
        while (seen.size > maxEntries) seen.remove(seen.first())
        return true
    }
}

/** Fail-closed settings for WebViews owned by OpenClone; cloned-app WebViews are out of scope. */
data class ManagedWebViewPolicy(
    val javaScriptEnabled: Boolean = false,
    val domStorageEnabled: Boolean = false,
    val allowFileAccess: Boolean = false,
    val allowContentAccess: Boolean = false,
    val mixedContentAllowed: Boolean = false,
    val allowedHosts: Set<String> = emptySet()
) {
    fun allows(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && host in allowedHosts.map(String::lowercase).toSet()
    }

    fun applyTo(webView: android.webkit.WebView) {
        webView.settings.apply {
            javaScriptEnabled = this@ManagedWebViewPolicy.javaScriptEnabled
            domStorageEnabled = this@ManagedWebViewPolicy.domStorageEnabled
            allowFileAccess = this@ManagedWebViewPolicy.allowFileAccess
            allowContentAccess = this@ManagedWebViewPolicy.allowContentAccess
            mixedContentMode = if (this@ManagedWebViewPolicy.mixedContentAllowed) {
                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean =
                !allows(request.url.toString())
        }
    }
}
