package org.openclone.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** A companion client for the narrowly-scoped v1 local Remote Control protocol. */
data class RemoteControlPairingEndpoint(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val secret: ByteArray
) {
    companion object {
        fun parse(value: String): RemoteControlPairingEndpoint {
            val uri = URI(value.trim())
            require(uri.scheme == "openclone" && uri.host == "pair") { "This is not an OpenClone pairing link." }
            val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }
                .associate { part ->
                    val pair = part.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
            require(params["v"] == "1") { "Unsupported Remote Control pairing version." }
            val host = params["host"].orEmpty()
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' }) {
                "Pairing link has an invalid host."
            }
            val port = params["port"]?.toIntOrNull() ?: error("Pairing link has no valid port.")
            require(port in 1..65535)
            val fingerprint = params["fingerprint"].orEmpty().lowercase()
            require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Pairing link has an invalid certificate fingerprint." }
            val secret = Base64.decode(params["secret"] ?: error("Pairing link has no secret."),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(secret.size == 32) { "Pairing secret has an invalid length." }
            return RemoteControlPairingEndpoint(host, port, fingerprint, secret)
        }
    }
}

/**
 * TLS is accepted only when the peer's leaf certificate exactly matches the
 * SHA-256 fingerprint delivered out-of-band in the pairing URI.
 */
object RemoteControlCompanionClient {
    fun status(pairing: RemoteControlPairingEndpoint): String = request(pairing, "GET", "/v1/status")
    fun launch(pairing: RemoteControlPairingEndpoint, packageName: String): String {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "Enter a valid Android package name."
        }
        return request(pairing, "POST", "/v1/launch/$packageName")
    }

    private fun request(pairing: RemoteControlPairingEndpoint, method: String, path: String): String {
        val response = requestRaw(pairing, method, path)
        return response.toString(StandardCharsets.UTF_8)
    }

    /** Fetches a JPEG snapshot over the same pinned TLS + HMAC authenticated channel. */
    fun screenFrame(pairing: RemoteControlPairingEndpoint): ByteArray =
        requestRaw(pairing, "GET", "/v1/screen/frame")

    private fun requestRaw(pairing: RemoteControlPairingEndpoint, method: String, path: String): ByteArray {
        val expected = pairing.fingerprint
        val requestSecret = pairing.secret.copyOf()
        val trustManager = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "Remote device did not provide a TLS certificate." }
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                    throw java.security.cert.CertificateException("Remote device certificate fingerprint does not match the pairing link.")
                }
                chain[0].checkValidity()
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        val raw = Socket()
        try {
            raw.connect(java.net.InetSocketAddress(pairing.host, pairing.port), 8000)
            raw.soTimeout = 8000
            val socket = context.socketFactory.createSocket(raw, pairing.host, pairing.port, true) as SSLSocket
            try {
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.startHandshake()
                val timestamp = System.currentTimeMillis().toString()
                val nonceBytes = ByteArray(18).also(SecureRandom()::nextBytes)
                val nonce = Base64.encodeToString(nonceBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val canonical = "$method\n$path\n$timestamp\n$nonce"
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(requestSecret, "HmacSHA256"))
                val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val request = "$method $path HTTP/1.1\r\nHost: ${pairing.host}:${pairing.port}\r\n" +
                    "X-OpenClone-Timestamp: $timestamp\r\nX-OpenClone-Nonce: $nonce\r\n" +
                    "X-OpenClone-Signature: $signature\r\nConnection: close\r\n\r\n"
                socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
                socket.outputStream.flush()
                val response = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) break
                    require(response.size() + count <= 4 * 1024 * 1024 + 8192) { "Remote response exceeded the size limit." }
                    response.write(buffer, 0, count)
                }
                val bytes = response.toByteArray()
                val headerEnd = (0 until (bytes.size - 3).coerceAtLeast(0)).firstOrNull { index ->
                    bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() &&
                        bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte()
                } ?: error("Remote response headers were incomplete.")
                val header = String(bytes, 0, headerEnd, StandardCharsets.US_ASCII)
                val statusLine = header.substringBefore("\r\n")
                require(statusLine.contains(" 200 ")) {
                    "Remote request failed: $statusLine · " +
                        String(bytes, headerEnd + 4, bytes.size - headerEnd - 4, StandardCharsets.UTF_8).take(240)
                }
                return bytes.copyOfRange(headerEnd + 4, bytes.size)
            } finally {
                socket.close()
            }
        } finally {
            requestSecret.fill(0)
            raw.close()
        }
    }
}


@androidx.compose.runtime.Composable
fun RemoteControlCompanionCard() {
    var pairingUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var packageName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Paste a pairing link received from a trusted OpenClone device.") }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var screenBitmap by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.Card {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Text("📲 Remote Control Companion", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Text(
                "Connect to another OpenClone device on the same trusted LAN. The companion verifies the exact TLS certificate fingerprint from the pairing link before sending authenticated requests.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.OutlinedTextField(
                value = pairingUri, onValueChange = { pairingUri = it.take(2048) },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(), minLines = 2,
                label = { androidx.compose.material3.Text("OpenClone pairing link") }
            )
            androidx.compose.material3.OutlinedTextField(
                value = packageName, onValueChange = { packageName = it.take(255) },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(), singleLine = true,
                label = { androidx.compose.material3.Text("Clone package to launch") }
            )
            androidx.compose.material3.Button(enabled = !busy && pairingUri.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val endpoint = RemoteControlPairingEndpoint.parse(pairingUri)
                            val bytes = RemoteControlCompanionClient.screenFrame(endpoint)
                            require(bytes.size in 1..(4 * 1024 * 1024)) { "Screen snapshot size is invalid." }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: error("Remote device returned an invalid screen image.")
                        }
                    }
                    result.fold(
                        { bitmap -> screenBitmap = bitmap; message = "Authenticated screen snapshot received." },
                        { error -> message = error.message ?: "Could not retrieve screen snapshot." }
                    )
                    busy = false
                }
            }) { androidx.compose.material3.Text("View screen snapshot") }
            screenBitmap?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Consent-based remote phone screen snapshot",
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(enabled = !busy && pairingUri.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { RemoteControlCompanionClient.status(RemoteControlPairingEndpoint.parse(pairingUri)) }
                        }
                        message = result.fold({ "Connected: $it" }, { it.message ?: "Could not connect to remote device." })
                        busy = false
                    }
                }) { androidx.compose.material3.Text(if (busy) "Connecting…" else "Check connection") }
                androidx.compose.material3.Button(enabled = !busy && pairingUri.isNotBlank() && packageName.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { RemoteControlCompanionClient.launch(RemoteControlPairingEndpoint.parse(pairingUri), packageName.trim()) }
                        }
                        message = result.fold({ "Launch request accepted: $it" }, { it.message ?: "Could not launch clone." })
                        busy = false
                    }
                }) { androidx.compose.material3.Text("Launch clone") }
            }
            androidx.compose.material3.Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}
