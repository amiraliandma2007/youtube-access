package com.directaccess.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.directaccess.app.R
import com.directaccess.app.core.AssetManagerHelper
import com.directaccess.app.core.VpnState
import com.directaccess.app.core.VpnStatus
import com.directaccess.app.ui.MainActivity
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class AccessVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.directaccess.app.action.START"
        const val ACTION_STOP = "com.directaccess.app.action.STOP"

        private const val CHANNEL_ID = "direct_access_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TUN_MTU = 1500
        private const val TUN_IPV4 = "10.10.14.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val SOCKS_PORT = 10808
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var coreController: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    lifecycleMutex.withLock { stopTunnelLocked() }
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                // Promote to foreground IMMEDIATELY to satisfy Android 12–16 FGS timeout
                ensureNotificationChannel()
                promoteForeground(connected = false)
                VpnState.update(VpnStatus.Connecting)

                serviceScope.launch {
                    lifecycleMutex.withLock { startTunnelLocked() }
                }
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        serviceScope.launch {
            lifecycleMutex.withLock { stopTunnelLocked() }
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.launch {
            lifecycleMutex.withLock { stopTunnelLocked() }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun startTunnelLocked() {
        stopTunnelLocked(updateState = false)

        try {
            val assetDir = AssetManagerHelper.ensureAssets(this)
            val configJson = File(assetDir, "config.json").readText(Charsets.UTF_8)

            Libv2ray.initCoreEnv(assetDir.absolutePath, "")

            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
            }

            val controller = Libv2ray.newCoreController(callback)
            coreController = controller
            // tunFd = 0 because hev-socks5-tunnel bridges TUN -> 127.0.0.1:10808
            controller.startLoop(configJson, 0)

            var ipv6Active = false
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName) // Critical: prevents Xray socket loop

            runCatching {
                builder.addAddress(TUN_IPV6, 126)
                builder.addRoute("::", 0)
                builder.addDnsServer("2606:4700:4700::1111")
                ipv6Active = true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException("VPN permission not granted or revoked")
            tunInterface = pfd

            val hevConfigFile = writeHevConfig(ipv6Active)
            val started = TProxyService.TProxyStartService(hevConfigFile.absolutePath, pfd.fd)
            if (!started) {
                throw IllegalStateException("Failed to start TUN bridge")
            }

            promoteForeground(connected = true)
            VpnState.update(VpnStatus.Connected(SystemClock.elapsedRealtime()))
        } catch (e: Exception) {
            stopTunnelLocked(updateState = false)
            VpnState.update(VpnStatus.Error(e.message ?: getString(R.string.status_error)))
            stopSelf()
        }
    }

    private fun stopTunnelLocked(updateState: Boolean = true) {
        // Strict teardown order: TUN bridge -> TUN fd -> Xray core
        runCatching {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        }
        runCatching { tunInterface?.close() }
        tunInterface = null

        runCatching {
            coreController?.let {
                if (it.isRunning) it.stopLoop()
            }
        }
        coreController = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (updateState) {
            VpnState.update(VpnStatus.Disconnected)
        }
    }

    private fun writeHevConfig(ipv6Active: Boolean): File {
        val file = File(filesDir, "hev-socks5-tunnel.yaml")
        val ipv6Line = if (ipv6Active) "  ipv6: '$TUN_IPV6'\n" else ""
        val yaml = buildString {
            append("tunnel:\n")
            append("  mtu: $TUN_MTU\n")
            append("  ipv4: $TUN_IPV4\n")
            append(ipv6Line)
            append("socks5:\n")
            append("  port: $SOCKS_PORT\n")
            append("  address: 127.0.0.1\n")
            append("  udp: 'udp'\n")
            append("misc:\n")
            append("  task-stack-size: 81920\n")
            append("  connect-timeout: 5000\n")
            append("  read-write-timeout: 60000\n")
            append("  log-level: error\n")
        }
        file.writeText(yaml, Charsets.UTF_8)
        return file
    }

    private fun promoteForeground(connected: Boolean) {
        val notif = buildNotification(connected)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, fgsType)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(connected: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AccessVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.app_name)
        val text = if (connected) {
            getString(R.string.notif_connected)
        } else {
            getString(R.string.status_connecting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
