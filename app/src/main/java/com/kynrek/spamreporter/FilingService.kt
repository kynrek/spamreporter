package com.kynrek.spamreporter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a filing run alive when you switch apps or the screen turns off: a foreground service (with an ongoing
 * "Filing spam reports" notification), a partial wake lock so the CPU keeps running, and a Wi-Fi lock so the connection
 * isn't dropped. The filing itself still runs in the Filing screen; this only stops Android from pausing or killing it.
 * Started when a run starts and stopped when it ends (or the screen is closed).
 */
class FilingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, i?.getStringExtra("text") ?: "Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpamReporter:filing").apply { acquire(3 * 60 * 60_000L) }   // safety cap: 3 h
        if (wifi == null) wifi = @Suppress("DEPRECATION") (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL, "SpamReporter:filing")
            .apply { setReferenceCounted(false); acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (wake?.isHeld == true) wake?.release() }; runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 42
        private const val CH = "filing_status"      // live status (setting on, default)
        private const val CH_MIN = "filing_quiet"   // setting off: Android still requires a notification; this one stays collapsed and silent
        /** Settings → "Status notification while filing" (default on). */
        fun statusOn(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("filing_notif", true)
        fun setStatusOn(c: Context, on: Boolean) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("filing_notif", on).apply()

        private fun notification(c: Context, text: String, done: Int = 0, total: Int = 0): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Filing status", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CH_MIN) == null) nm.createNotificationChannel(NotificationChannel(CH_MIN, "Filing (quiet)", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(c, 0, Intent(c, FilingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val live = statusOn(c)
            return Notification.Builder(c, if (live) CH else CH_MIN).setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(if (live) "Filing spam reports" else "Spam Reporter is filing")
                .apply { if (live) { setContentText(text); if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) } }
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        }
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, FilingService::class.java)) }
        fun update(c: Context, text: String, done: Int, total: Int) {
            if (!statusOn(c)) return
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, notification(c, text, done, total)) }
        }
        fun stop(c: Context) = runCatching { c.stopService(Intent(c, FilingService::class.java)) }
    }
}
