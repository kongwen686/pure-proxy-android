package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.VpnSessionDiagnostics
import java.text.DateFormat
import java.util.Date

class BackgroundActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background)
        title = getString(R.string.background_title)
        findViewById<Button>(R.id.background_battery_button).setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.background_app_button).setOnClickListener { openSettings(appSettings()) }
        findViewById<Button>(R.id.background_notification_button).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "PURE_PROXY_VPN_STATUS_V2")
            } else appSettings()
            openSettings(intent)
        }
        findViewById<Button>(R.id.background_vpn_button).setOnClickListener {
            openSettings(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        findViewById<Button>(R.id.background_copy).setOnClickListener {
            val text = "Pure Proxy ${BuildConfig.VERSION_NAME}; Android API ${Build.VERSION.SDK_INT}\n" +
                batteryText() + "\n" + notificationsText() + "\n" + eventText()
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.background_title), text))
            Toast.makeText(this, R.string.background_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.background_battery_status).text = batteryText()
        findViewById<TextView>(R.id.background_notifications_status).text = notificationsText()
        findViewById<TextView>(R.id.background_last_event).text = eventText()
    }

    private fun batteryText(): String {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        return getString(R.string.background_battery_status, getString(if (exempt) R.string.background_yes else R.string.background_no))
    }

    private fun notificationsText(): String {
        var allowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = getSystemService(android.app.NotificationManager::class.java)
                .getNotificationChannel("PURE_PROXY_VPN_STATUS_V2")
            if (channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE) allowed = false
        }
        return getString(R.string.background_notifications_status,
            getString(if (allowed) R.string.background_notifications_enabled else R.string.background_notifications_disabled))
    }

    private fun eventText(): String {
        val event = VpnSessionDiagnostics.lastEvent() ?: return getString(R.string.background_no_event)
        val label = when (event.second) {
            "STARTING" -> R.string.background_starting_event
            "CONNECTED" -> R.string.background_connected_event
            "USER_STOP" -> R.string.background_user_stop
            "USER_RESTART" -> R.string.background_user_restart
            "VPN_REVOKED" -> R.string.background_revoked
            "CORE_STOPPED" -> R.string.background_core_stopped
            "START_FAILED" -> R.string.background_start_failed
            "SERVICE_DESTROYED" -> R.string.background_service_destroyed
            else -> R.string.background_unknown
        }
        return getString(R.string.background_last_event,
            DateFormat.getDateTimeInstance().format(Date(event.first)), getString(label))
    }

    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun openSettings(intent: Intent) {
        // Do not change settings automatically. Firmware may omit individual settings activities.
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(appSettings())
            } catch (_: Exception) {
                Toast.makeText(this, R.string.background_settings_missing, Toast.LENGTH_LONG).show()
            }
        }
    }
}
