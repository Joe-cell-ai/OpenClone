package org.openclone.app

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
        val expected = pairing.fingerprint
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
        raw.connect(java.net.InetSocketAddress(pairing.host, pairing.port), 8000)
        raw.soTimeout = 8000
        val socket = context.socketFactory.createSocket(raw, pairing.host, pairing.port, true) as SSLSocket
        try {
            socket.enabledProtocols = arrayOf("TLSv1.2")
            socket.startHandshake()
            val timestamp = System.currentTimeMillis().toString()
            val nonceBytes = ByteArray(18).also(SecureRandom()::nextBytes)
            val nonce = Base64.encodeToString(nonceBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val canonical = "$method\\n$path\\n$timestamp\\n$nonce"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pairing.secret, "HmacSHA256"))
            val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val request = "$method $path HTTP/1.1\\r\\nHost: ${pairing.host}:${pairing.port}\\r\\n" +
                "X-OpenClone-Timestamp: $timestamp\\r\\nX-OpenClone-Nonce: $nonce\\r\\n" +
                "X-OpenClone-Signature: $signature\\r\\nConnection: close\\r\\n\\r\\n"
            socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.outputStream.flush()
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = socket.inputStream.read(buffer)
                if (count < 0) break
                require(response.size() + count <= 16 * 1024) { "Remote response exceeded the size limit." }
                response.write(buffer, 0, count)
            }
            val text = response.toString("UTF-8")
            val statusLine = text.substringBefore("\\r\\n")
            require(statusLine.contains(" 200 ")) { "Remote request failed: $statusLine · ${text.substringAfter("\\r\\n\\r\\n").take(240)}" }
            return text.substringAfter("\\r\\n\\r\\n", "").take(1000)
        } finally {
            pairing.secret.fill(0)
            socket.close()
        }
    }
}
