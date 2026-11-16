package org.openclone.app.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/**
 * DNS-only local VPN. It routes only the synthetic DNS endpoint through the TUN;
 * ordinary device traffic remains on Android's selected network.
 */
class AdShieldVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "org.openclone.app.adshield.STOP"
        private const val CHANNEL = "openclone_ad_shield"
        private const val NOTIFICATION_ID = 7301
        private const val PREFS = "openclone_ad_shield"
        private const val BLOCKED = "blocked_domains"
        private const val ALLOWED = "allowed_domains"
        private const val DNS = "https://cloudflare-dns.com/dns-query"
        private val running = AtomicBoolean(false)
        private val blockedCount = AtomicLong(0)
        private val forwardedCount = AtomicLong(0)

        fun isRunning(): Boolean = running.get()
        fun blockedQueries(): Long = blockedCount.get()
        fun forwardedQueries(): Long = forwardedCount.get()
    }

    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        createChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else Notification.Builder(this)
        startForeground(NOTIFICATION_ID, notification
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("OpenClone Ad Shield")
            .setContentText("DNS filtering is active on this device")
            .setOngoing(true).build())

        val descriptor = Builder()
            .setSession("OpenClone Ad Shield")
            .setMtu(1500)
            .addAddress("10.111.0.1", 32)
            .addDnsServer("10.111.0.2")
            .addRoute("10.111.0.2", 32)
            .establish()
        if (descriptor == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        tunnel = descriptor
        running.set(true)
        worker = Thread({ packetLoop(descriptor) }, "OpenClone-AdShield-DNS").apply {
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    private fun packetLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (running.get()) {
                val count = input.read(buffer)
                if (count <= 0) continue
                val packet = buffer.copyOf(count)
                val response = handleIpv4UdpDns(packet) ?: continue
                output.write(response)
            }
        } catch (_: Exception) {
            // Closing the descriptor is the normal way to end the blocking read.
        } finally {
            running.set(false)
            runCatching { descriptor.close() }
            if (tunnel === descriptor) tunnel = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleIpv4UdpDns(packet: ByteArray): ByteArray? {
        if (packet.size < 28 || (packet[0].toInt() ushr 4 and 0xf) != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || packet.size < ipHeaderLength + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null
        val fragment = u16(packet, 6) ?: return null
        if ((fragment and 0x3fff) != 0) return null
        val sourcePort = u16(packet, ipHeaderLength) ?: return null
        val destinationPort = u16(packet, ipHeaderLength + 2) ?: return null
        if (destinationPort != 53) return null
        val udpLength = u16(packet, ipHeaderLength + 4) ?: return null
        if (udpLength < 8 || ipHeaderLength + udpLength > packet.size) return null
        val dns = packet.copyOfRange(ipHeaderLength + 8, ipHeaderLength + udpLength)
        val question = DnsWireCodec.parseSingleQuestion(dns) ?: return null
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val policy = DomainBlocklist.fromLines(
            prefs.getString(BLOCKED, "").orEmpty().lineSequence().toList(),
            prefs.getString(ALLOWED, "").orEmpty().lineSequence().toList()
        )
        val answer = if (policy.isBlocked(question.hostname)) {
            blockedCount.incrementAndGet()
            DnsWireCodec.buildNxDomainResponse(dns, question)
        } else {
            forwardedCount.incrementAndGet()
            resolveOverHttps(dns) ?: return null
        }
        val sourceAddress = packet.copyOfRange(12, 16)
        val destinationAddress = packet.copyOfRange(16, 20)
        return ipv4UdpResponse(destinationAddress, sourceAddress, destinationPort, sourcePort, answer)
    }

    private fun resolveOverHttps(query: ByteArray): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val upstream = manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: return null
            // Bind DoH to the physical upstream network to avoid recursive DNS through this VPN.
            connection = upstream.openConnection(URL(DNS)) as HttpsURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            if (connection.responseCode != 200) return null
            connection.inputStream.use { it.readBytes().takeIf { bytes -> bytes.size in 12..4096 } }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun ipv4UdpResponse(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, dns: ByteArray): ByteArray {
        val udpLength = 8 + dns.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        src.copyInto(packet, 12)
        dst.copyInto(packet, 16)
        putU16(packet, 10, checksum(packet, 0, 20))
        putU16(packet, 20, srcPort)
        putU16(packet, 22, dstPort)
        putU16(packet, 24, udpLength)
        dns.copyInto(packet, 28)
        return packet
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val high = data[i].toInt() and 0xff
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xff else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(bytes: ByteArray, offset: Int): Int? =
        if (offset < 0 || offset + 1 >= bytes.size) null
        else ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ad Shield", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun stopTunnel() {
        running.set(false)
        runCatching { tunnel?.close() }
        tunnel = null
        worker?.interrupt()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
