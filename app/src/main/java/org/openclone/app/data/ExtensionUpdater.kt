package org.openclone.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** A publisher key is trusted only after the user confirms its displayed SHA-256 fingerprint. */
data class TrustedPublisher(val id: String, val label: String, val publicKeyBase64: String, val fingerprintSha256: String)

class TrustedPublisherRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openclone_trusted_publishers", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<TrustedPublisher> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val publisher = TrustedPublisher(item.optString("id"), item.optString("label"),
                    item.optString("key"), item.optString("fingerprint"))
                runCatching { validatePublisher(publisher) }.onSuccess { add(publisher) }
            }
        }
    }.getOrDefault(emptyList())

    /** Returns the fingerprint to display before the user explicitly trusts this key. */
    fun inspect(label: String, encodedPublicKey: String): TrustedPublisher {
        require(label.isNotBlank() && label.length <= 120) { "Publisher name must contain 1–120 characters." }
        val keyBytes = decodeKey(encodedPublicKey)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        require(key.algorithm.equals("EC", true)) { "Only EC publisher keys are supported." }
        val fingerprint = fingerprint(key.encoded)
        return TrustedPublisher(fingerprint.take(24), label.trim(), Base64.getEncoder().encodeToString(key.encoded), fingerprint)
    }

    @Synchronized fun trust(candidate: TrustedPublisher, confirmationFingerprint: String) {
        validatePublisher(candidate)
        require(confirmationFingerprint.replace(Regex("[:\\s]"), "").equals(candidate.fingerprintSha256, true)) {
            "Publisher fingerprint confirmation does not match."
        }
        val current = all()
        val existing = current.filterNot { it.id == candidate.id }
        require(candidate.id in current.map { it.id } || existing.size < MAX_PUBLISHERS) {
            "A maximum of 32 trusted publishers can be stored."
        }
        val updated = (existing + candidate).sortedBy { it.label.lowercase() }
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("label", item.label)
                .put("key", item.publicKeyBase64).put("fingerprint", item.fingerprintSha256))
        }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "Could not save trusted publisher." }
    }

    @Synchronized fun revoke(id: String) {
        val array = JSONArray()
        all().filterNot { it.id == id }.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("label", item.label)
                .put("key", item.publicKeyBase64).put("fingerprint", item.fingerprintSha256))
        }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "Could not revoke publisher." }
    }

    fun publicKey(publisherId: String): PublicKey? = all().firstOrNull { it.id == publisherId }?.let {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it.publicKeyBase64)))
    }

    fun verifyCatalogue(envelope: SignedExtensionCatalogue, currentApi: Int): List<CatalogueExtension>? {
        val key = publicKey(envelope.publisherId) ?: return null
        return ExtensionCatalogueVerifier.verify(envelope, key, currentApi)
    }

    fun verifyPackage(entry: CatalogueExtension, packageBytes: ByteArray, currentApi: Int): Boolean {
        val key = publicKey(entry.publisherId) ?: return false
        return ExtensionPackageVerifier.verify(entry.descriptor(), packageBytes, key, currentApi)
    }

    companion object {
        private const val KEY = "publishers"
        private const val MAX_PUBLISHERS = 32

        private fun validatePublisher(item: TrustedPublisher) {
            require(item.label.isNotBlank() && item.label.length <= 120)
            val bytes = decodeKey(item.publicKeyBase64)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            require(fingerprint(key.encoded).equals(item.fingerprintSha256, true))
            require(item.id == item.fingerprintSha256.take(24))
        }

        private fun decodeKey(value: String): ByteArray {
            val normalized = value.replace(Regex("-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\\s"), "")
            val bytes = Base64.getDecoder().decode(normalized)
            require(bytes.size in 64..512) { "Publisher key has an invalid size." }
            return bytes
        }

        private fun fingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Signed catalogue envelope. The exact UTF-8 payload bytes are verified before JSON parsing. */
data class SignedExtensionCatalogue(val publisherId: String, val payloadBase64: String, val signatureBase64: String)

data class CatalogueExtension(
    val id: String,
    val version: String,
    val publisher: String,
    val minimumOpenCloneApi: Int,
    val maximumOpenCloneApi: Int,
    val sha256: String,
    val downloadUrl: String,
    val signatureBase64: String,
    val publisherId: String
) {
    fun descriptor() = SignedExtensionDescriptor(id, version, publisher, minimumOpenCloneApi,
        maximumOpenCloneApi, sha256, signatureBase64)
}

object ExtensionCatalogueVerifier {
    fun verify(envelope: SignedExtensionCatalogue, publisherKey: PublicKey, currentApi: Int): List<CatalogueExtension>? =
        runCatching {
            val payload = Base64.getDecoder().decode(envelope.payloadBase64)
            require(payload.size in 2..(1024 * 1024)) { "Catalogue size is invalid." }
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publisherKey)
            signature.update(payload)
            require(signature.verify(Base64.getDecoder().decode(envelope.signatureBase64))) { "Catalogue signature is invalid." }
            val root = JSONObject(String(payload, Charsets.UTF_8))
            require(root.optInt("formatVersion") == 1) { "Unsupported catalogue format." }
            val items = root.optJSONArray("extensions") ?: JSONArray()
            require(items.length() <= 200) { "Catalogue contains too many entries." }
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val entry = CatalogueExtension(
                        item.getString("id"), item.getString("version"), item.getString("publisher"),
                        item.getInt("minimumOpenCloneApi"), item.getInt("maximumOpenCloneApi"),
                        item.getString("sha256"), item.getString("downloadUrl"), item.getString("signature"),
                        envelope.publisherId
                    )
                    entry.descriptor().canonicalPayload()
                    val uri = java.net.URI(entry.downloadUrl)
                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
                        "Extension downloads must use HTTPS."
                    }
                    if (currentApi in entry.minimumOpenCloneApi..entry.maximumOpenCloneApi) add(entry)
                }
            }
        }.getOrNull()
}

/** Fetches a compact signed envelope and verifies it against the envelope's explicitly trusted publisher. */
object ExtensionCatalogueClient {
    fun fetchAndVerify(url: String, publishers: TrustedPublisherRepository, currentApi: Int): List<CatalogueExtension> {
        val bytes = ExtensionPackageDownloader.download(url, maxBytes = 1024 * 1024)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val envelope = SignedExtensionCatalogue(
            publisherId = root.getString("publisherId"),
            payloadBase64 = root.getString("payload"),
            signatureBase64 = root.getString("signature")
        )
        return publishers.verifyCatalogue(envelope, currentApi)
            ?: throw SecurityException("Catalogue signature is invalid or its publisher is not trusted.")
    }
}

/** HTTPS-only bounded downloader; callers must verify the returned bytes before installation. */
object ExtensionPackageDownloader {
    fun download(url: String, maxBytes: Int = 100 * 1024 * 1024): ByteArray {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Only HTTPS download URLs are accepted." }
        require(maxBytes in 1..(100 * 1024 * 1024))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        try {
            require(connection.responseCode in 200..299) { "Extension download failed (HTTP ${connection.responseCode})." }
            val declared = connection.contentLengthLong
            require(declared <= maxBytes) { "Extension package exceeds the download limit." }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Extension package exceeds the download limit." }
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "Downloaded extension package is empty." }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Uses Android's package installer session; Android still displays and controls user confirmation. */
object ExtensionPackageInstaller {
    fun stage(context: Context, packageBytes: ByteArray, packageName: String): Int {
        require(packageBytes.isNotEmpty() && packageBytes.size <= 100 * 1024 * 1024)
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, packageBytes.size.toLong()).use { output ->
                    output.write(packageBytes)
                    session.fsync(output)
                }
                val callback = android.app.PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, ExtensionInstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                )
                session.commit(callback.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return sessionId
    }

    const val ACTION_INSTALL_STATUS = "org.openclone.app.EXTENSION_INSTALL_STATUS"
}

/** Receives only Android PackageInstaller's result; it does not grant install permissions. */
class ExtensionInstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(240)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
        ExtensionDiagnosticsLog(context).append("updater", "install_status_$status", message.ifBlank { "Android package installer returned status $status." })
    }
}
