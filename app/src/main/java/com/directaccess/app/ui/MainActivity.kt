package com.directaccess.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.directaccess.app.R
import com.directaccess.app.core.VpnState
import com.directaccess.app.core.VpnStatus
import com.directaccess.app.vpn.AccessVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: PulseConnectButton
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var hintText: TextView
    private var timerJob: Job? = null

    private val prefs by lazy { getSharedPreferences("direct_access_prefs", MODE_PRIVATE) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            requestNotifThenStart()
        } else {
            VpnState.update(VpnStatus.Disconnected)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Start regardless; foreground VPN service runs even if notification is denied
        startVpnService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root_container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        connectButton = findViewById(R.id.btn_connect)
        statusText = findViewById(R.id.txt_status)
        timerText = findViewById(R.id.txt_timer)
        hintText = findViewById(R.id.txt_hint)

        connectButton.setOnClickListener { handleConnectToggle() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnState.status.collect { renderStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePrivateDnsHint()
    }

    private fun handleConnectToggle() {
        when (VpnState.status.value) {
            is VpnStatus.Connected, is VpnStatus.Connecting -> stopVpnService()
            is VpnStatus.Disconnected, is VpnStatus.Error -> ensureConsentThenPrepareVpn()
        }
    }

    private fun ensureConsentThenPrepareVpn() {
        if (prefs.getBoolean("vpn_disclosure_accepted", false)) {
            prepareVpn()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                prefs.edit().putBoolean("vpn_disclosure_accepted", true).apply()
                prepareVpn()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prepareVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            requestNotifThenStart()
        }
    }

    private fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AccessVpnService::class.java).apply {
            action = AccessVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AccessVpnService::class.java).apply {
            action = AccessVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun renderStatus(status: VpnStatus) {
        timerJob?.cancel()
        when (status) {
            is VpnStatus.Disconnected -> {
                connectButton.state = PulseConnectButton.VisualState.DISCONNECTED
                statusText.setText(R.string.status_disconnected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                timerText.visibility = View.INVISIBLE
            }
            is VpnStatus.Connecting -> {
                connectButton.state = PulseConnectButton.VisualState.CONNECTING
                statusText.setText(R.string.status_connecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                timerText.visibility = View.INVISIBLE
            }
            is VpnStatus.Connected -> {
                connectButton.state = PulseConnectButton.VisualState.CONNECTED
                statusText.setText(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                timerText.visibility = View.VISIBLE
                startTimer(status.startedAtElapsedMs)
            }
            is VpnStatus.Error -> {
                connectButton.state = PulseConnectButton.VisualState.DISCONNECTED
                statusText.text = status.message
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                timerText.visibility = View.INVISIBLE
            }
        }
    }

    private fun startTimer(startedAtMs: Long) {
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val sec = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000L).coerceAtLeast(0L)
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60
                timerText.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                delay(1000L)
            }
        }
    }

    private fun updatePrivateDnsHint() {
        val mode = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull()
        hintText.visibility = if (mode == "hostname") View.VISIBLE else View.GONE
    }
}
