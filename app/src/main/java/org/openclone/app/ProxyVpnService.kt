package org.openclone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import org.openclone.app.data.ExtensionDiagnosticsLog
import java.net.InetAddress

/** Android VpnService routes only the selected clone; OpenClone and other apps bypass this tunnel. */
class ProxyVpnService : VpnService() {
    @Volatile private var running = false
    @Volatile private var tunDescriptor: android.os.ParcelFileDescriptor? = null
    private var engineThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) stopTunnel()

        val clonePackage = intent?.getStringExtra(EXTRA_CLONE_PACKAGE).orEmpty()
        val host = intent?.getStringExtra(EXTRA_PROXY_HOST).orEmpty()
        val port = intent?.getIntExtra(EXTRA_PROXY_PORT, -1) ?: -1
        val protocol = intent?.getStringExtra(EXTRA_PROXY_PROTOCOL).orEmpty()
        val validHost = host.isNotBlank() && host.length <= 253 &&
            host.none { it.isWhitespace() || it == '/' || it == '@' }
        if (!clonePackage.matches(PACKAGE_PATTERN) || !validHost || port !in 1..65535 ||
            protocol !in setOf("HTTP_CONNECT", "SOCKS5")) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startSessionNotification(clonePackage)
            val descriptor = Builder()
                .setSession("OpenClone proxy · " + clonePackage)
                .setMtu(TUN_MTU)
                .addAddress(InetAddress.getByName("10.77.0.2"), 32)
                .addAddress(InetAddress.getByName("fd77:77::2"), 128)
                .addDnsServer(InetAddress.getByName("198.18.0.1"))
                .addRoute(InetAddress.getByName("0.0.0.0"), 0)
                .addRoute(InetAddress.getByName("::"), 0)
                .addAllowedApplication(clonePackage)
                .establish() ?: error("Android did not establish the VPN interface.")
            tunDescriptor = descriptor
            val proxyHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val proxyScheme = if (protocol == "SOCKS5") "socks5" else "http"
            val proxyUrl = "$proxyScheme://$proxyHost:$port"
            val arguments = "--tun-fd ${descriptor.fd} --close-fd-on-drop false --proxy $proxyUrl --dns virtual --ipv6-enabled --verbosity warn"
            running = true
            engineThread = Thread({
                val result = runCatching { com.github.shadowsocks.bg.Tun2proxy.run(arguments, TUN_MTU.toChar()) }
                result.onFailure {
                    ExtensionDiagnosticsLog(this).append("proxy", "engine_failed", it.javaClass.simpleName)
                }
                if (result.isSuccess) {
                    ExtensionDiagnosticsLog(this).append("proxy", "engine_stopped", "TUN proxy engine exited with code " + result.getOrNull())
                }
                running = false
                runCatching { tunDescriptor?.close() }
                tunDescriptor = null
                stopSelf()
            }, "OpenCloneTun2Proxy").apply { isDaemon = true; start() }
            ExtensionDiagnosticsLog(this).append("proxy", "route_started", "Started an Android per-clone VPN route.")
        } catch (error: Throwable) {
            ExtensionDiagnosticsLog(this).append("proxy", "route_start_failed", error.javaClass.simpleName)
            stopTunnel()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSessionNotification(clonePackage: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Per-clone proxy routing", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, NOTIFICATION_ID, Intent(this, ProxyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("OpenClone proxy is active")
            .setContentText("Routing only $clonePackage through its selected proxy")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel() {
        running = false
        runCatching { com.github.shadowsocks.bg.Tun2proxy.stop() }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        engineThread?.interrupt()
        engineThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        ExtensionDiagnosticsLog(this).append("proxy", "route_stopped", "Per-clone VPN route stopped.")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.openclone.app.PROXY_VPN_STOP"
        const val EXTRA_CLONE_PACKAGE = "clone_package"
        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_PROXY_PROTOCOL = "proxy_protocol"
        private const val CHANNEL = "openclone_proxy_vpn"
        private const val NOTIFICATION_ID = 38474
        private const val TUN_MTU = 1500
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
