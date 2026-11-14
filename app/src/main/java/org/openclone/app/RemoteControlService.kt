package org.openclone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openclone.app.data.ExtensionDiagnosticsLog
import org.openclone.app.data.InstalledAppRepository
import org.openclone.app.data.InstalledCloneRepository
import org.openclone.app.data.RemoteControlAuthenticator
import org.openclone.app.data.RemoteControlReplayGuard
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

private const val REMOTE_CHANNEL = "openclone_remote_control"
private const val REMOTE_PORT = 38473
private const val REMOTE_STOP = "org.openclone.app.REMOTE_STOP"

data class RemotePairingInfo(val secretBase64Url: String, val certificateFingerprint: String, val host: String, val port: Int) {
    fun toUri(): String = "openclone://pair?v=1&host=$host&port=$port&fingerprint=$certificateFingerprint&secret=$secretBase64Url"
}

/** Pairing credentials are encrypted with an Android Keystore AES-GCM key and never stored as plaintext. */
object RemoteControlPairingStore {
    private const val KEY_ALIAS = "openclone_remote_pairing_aes"
    private const val PREFS = "openclone_remote_pairing"
    private const val CREDENTIAL = "encrypted_secret"

    fun create(context: Context): RemotePairingInfo {
        val secret = RemoteControlAuthenticator.createPairingSecret()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(secret)
        val encoded = android.util.Base64.encodeToString(cipher.iv + encrypted, android.util.Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CREDENTIAL, encoded).commit()) {
            "Could not save the encrypted pairing credential."
        }
        val info = RemotePairingInfo(
            android.util.Base64.encodeToString(secret, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
            RemoteControlTlsIdentity.fingerprint(),
            RemoteControlNetworkAddress.find(context),
            REMOTE_PORT
        )
        secret.fill(0)
        return info
    }

    fun read(context: Context): ByteArray? {
        return runCatching {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CREDENTIAL, null) ?: return@runCatching null
            val packed = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            require(packed.size > 12 + 16)
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CREDENTIAL).commit()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(CREDENTIAL)

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** Stable self-signed EC identity generated and retained by Android Keystore; clients pin its SHA-256 certificate fingerprint. */
object RemoteControlTlsIdentity {
    private const val ALIAS = "openclone_remote_tls_identity"

    fun fingerprint(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=OpenClone Remote Control"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now.coerceAtLeast(1)))
                    .setCertificateNotBefore(Date(now - 60_000))
                    .setCertificateNotAfter(Date(now + 365L * 24 * 60 * 60 * 1000))
                    .build()
            )
            generator.generateKeyPair()
        }
        val certificate: Certificate = store.getCertificate(ALIAS)
            ?: throw IllegalStateException("Remote TLS certificate is unavailable.")
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    fun sslContext(): SSLContext {
        fingerprint()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, null)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
    }
}

object RemoteControlNetworkAddress {
    /** Prefer a non-VPN LAN interface; never publish a TUN/VPN address as the pairing host. */
    fun find(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val priority = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 3
            }
            val address = manager.getLinkProperties(network)?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: return@mapNotNull null
            priority to address.hostAddress
        }
        return candidates.minByOrNull { it.first }?.second
            ?: throw IllegalStateException("No reachable non-VPN IPv4 network address was found. Connect to local Wi-Fi or Ethernet and try again.")
    }
}

/** User-started TLS 1.2 server. It exposes only status and launch of OpenClone-created clones. */
class RemoteControlService : Service() {
    @Volatile private var running = false
    @Volatile private var serverSocket: SSLServerSocket? = null
    private var serverThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REMOTE_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(REMOTE_CHANNEL, "Remote control session", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 38473, Intent(this, RemoteControlService::class.java).setAction(REMOTE_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, REMOTE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenClone remote control is active")
            .setContentText("TLS-protected local network session · port $REMOTE_PORT")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build())
            .build()
        startForeground(38473, notification)
        running = true
        serverThread = Thread({ serve() }, "OpenCloneRemoteControl").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun serve() {
        try {
            val socket = RemoteControlTlsIdentity.sslContext().serverSocketFactory
                .createServerSocket(REMOTE_PORT) as SSLServerSocket
            socket.enabledProtocols = arrayOf("TLSv1.2")
            socket.soTimeout = 1000
            serverSocket = socket
            while (running) {
                try {
                    val accepted = socket.accept() as javax.net.ssl.SSLSocket
                    accepted.use { client ->
                        client.soTimeout = 5000
                        client.startHandshake()
                        handleRequest(client)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Allows the stop action to be observed without holding the service thread indefinitely.
                }
            }
        } catch (error: Throwable) {
            ExtensionDiagnosticsLog(this).append("remote", "server_failed", error.javaClass.simpleName)
        } finally {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            stopSelf()
        }
    }

    private fun handleRequest(socket: javax.net.ssl.SSLSocket) {
        val secret = RemoteControlPairingStore.read(this) ?: return respond(socket, 401, "Pairing revoked")
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
            val requestLine = readBoundedLine(reader, 2048) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1") return respond(socket, 400, "Bad request")
            val method = parts[0]
            val path = parts[1]
            if (path.length > 512 || path.contains('?') || path.contains('#')) return respond(socket, 400, "Invalid path")
            val headers = mutableMapOf<String, String>()
            var totalHeaderBytes = 0
            while (true) {
                val line = readBoundedLine(reader, 2048) ?: return respond(socket, 400, "Bad headers")
                totalHeaderBytes += line.length
                if (totalHeaderBytes > 8192) return respond(socket, 431, "Headers too large")
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split <= 0) return respond(socket, 400, "Bad header")
                headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
            }
            if ((headers["content-length"]?.toIntOrNull() ?: 0) != 0 ||
                headers["transfer-encoding"] != null) return respond(socket, 400, "Request bodies are not supported")
            val timestamp = headers["x-openclone-timestamp"]?.toLongOrNull() ?: return respond(socket, 401, "Missing authentication")
            val nonce = headers["x-openclone-nonce"] ?: return respond(socket, 401, "Missing authentication")
            val signature = headers["x-openclone-signature"] ?: return respond(socket, 401, "Missing authentication")
            if (!RemoteControlAuthenticator.verifyAndConsume(
                    secret, method, path, timestamp, nonce, signature, replayGuard
                )) return respond(socket, 401, "Authentication failed")
            when {
                method == "GET" && path == "/v1/status" -> {
                    ExtensionDiagnosticsLog(this).append("remote", "status_read", "Authenticated remote status request.")
                    respond(socket, 200, "{\"ok\":true,\"service\":\"OpenClone\",\"api\":1}", "application/json")
                }
                method == "GET" && path == "/v1/screen/frame" -> {
                    val frame = RemoteScreenFrameStore.latest()
                        ?: return respond(socket, 409, "No consented screen frame is currently available.")
                    try { respondBytes(socket, 200, frame, "image/jpeg") } finally { frame.fill(0) }
                }
                method == "POST" && path.startsWith("/v1/launch/") -> {
                    val packageName = path.removePrefix("/v1/launch/")
                    val clones = runCatching {
                        val apps = InstalledAppRepository(packageManager).getInstalledApps(true)
                        InstalledCloneRepository(packageManager).findClones(apps).map { it.clonePackage }.toSet()
                    }.getOrDefault(emptySet())
                    if (packageName !in clones) return respond(socket, 403, "Only OpenClone-created clones can be launched")
                    val launch = packageManager.getLaunchIntentForPackage(packageName)
                        ?: return respond(socket, 404, "Clone has no launch activity")
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    ExtensionDiagnosticsLog(this).append("remote", "clone_launched", "Authenticated request launched an OpenClone-created clone.")
                    respond(socket, 200, "{\"ok\":true}", "application/json")
                }
                else -> respond(socket, 404, "Unknown endpoint")
            }
        } catch (error: Throwable) {
            ExtensionDiagnosticsLog(this).append("remote", "request_failed", error.javaClass.simpleName)
            runCatching { respond(socket, 400, "Request failed") }
        } finally {
            secret.fill(0)
        }
    }

    private fun respondBytes(socket: javax.net.ssl.SSLSocket, status: Int, bytes: ByteArray, contentType: String) {
        val reason = if (status == 200) "OK" else "Error"
        val writer = OutputStreamWriter(socket.outputStream, Charsets.US_ASCII)
        writer.write("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\\r\\nContent-Length: ${bytes.size}\\r\\nConnection: close\\r\\n\\r\\n")
        writer.flush()
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val result = StringBuilder()
        while (true) {
            val char = reader.read()
            if (char < 0) return if (result.isEmpty()) null else result.toString()
            if (char == '\n'.code) return result.toString().removeSuffix("\r")
            if (result.length >= maxChars) throw IllegalArgumentException("HTTP line too long")
            result.append(char.toChar())
        }
    }

    private fun respond(socket: javax.net.ssl.SSLSocket, status: Int, body: String, contentType: String = "text/plain") {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = OutputStreamWriter(socket.outputStream, Charsets.US_ASCII)
        writer.write("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        writer.flush()
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    override fun onDestroy() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread?.interrupt()
        // Revoking the companion session also terminates any screen-capture stream.
        stopService(Intent(this, RemoteScreenCaptureService::class.java))
        RemoteScreenFrameStore.clear()
        RemoteControlPairingStore.revoke(this)
        ExtensionDiagnosticsLog(this).append("remote", "server_stopped", "Remote control server stopped and pairing credential revoked.")
        super.onDestroy()
    }

    companion object {
        private val replayGuard = RemoteControlReplayGuard(512)
    }
}
