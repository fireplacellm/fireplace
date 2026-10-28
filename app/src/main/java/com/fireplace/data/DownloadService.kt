package com.fireplace.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that performs the one-time setup download (model + pack).
 *
 * A model is 0.5–11 GB; a bare background thread would be killed the moment the
 * app is cached (screen off, app switch, memory pressure). Running the transfer
 * in a foreground service with a progress notification keeps the process alive
 * to completion - the resilience the OS download manager provided, kept in-app
 * so nothing but this one setup step ever touches the network.
 *
 * Progress flows back to the UI through [DownloadBus]; the notification mirrors it.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Downloader.cancel()
            return START_NOT_STICKY
        }

        val urls = intent?.getStringArrayExtra(EX_URLS) ?: return stopEarly()
        val subdirs = intent.getStringArrayExtra(EX_SUBDIRS) ?: return stopEarly()
        val names = intent.getStringArrayExtra(EX_NAMES) ?: return stopEarly()
        val sizes = intent.getLongArrayExtra(EX_SIZES) ?: return stopEarly()
        if (urls.isEmpty() || urls.size != names.size) return stopEarly()

        val files = urls.indices.map { DownloadFile(urls[it], subdirs[it], names[it], sizes[it]) }
        val total = files.sumOf { it.sizeBytes }
        DownloadBus.onStarted(total)

        ensureChannel()
        startForeground(NOTIF_ID, notif("Preparing download…", 0))

        val base = getExternalFilesDir(null)
        if (base == null) { DownloadBus.onComplete(false, "no storage"); stopSelf(); return START_NOT_STICKY }

        Thread {
            Downloader.run(
                base, files,
                onProgress = { p ->
                    DownloadBus.onProgress(p)
                    val pct = (p.fraction * 100).toInt()
                    val mb = p.doneBytes / 1_048_576
                    val totMb = p.totalBytes / 1_048_576
                    notify("Downloading… $mb / $totMb MB", pct)
                },
                onComplete = { ok, err ->
                    DownloadBus.onComplete(ok, err)
                    stopForegroundCompat()
                    stopSelf()
                },
            )
        }.apply { isDaemon = true; start() }

        return START_NOT_STICKY
    }

    private fun stopEarly(): Int { stopSelf(); return START_NOT_STICKY }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Setup downloads", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Shows progress while the model and knowledge pack download." }
                )
            }
        }
    }

    private fun notif(text: String, pct: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Fireplace")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .build()

    private fun notify(text: String, pct: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif(text, pct))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    companion object {
        private const val CHANNEL = "fp_downloads"
        private const val NOTIF_ID = 4201
        const val ACTION_START = "com.fireplace.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.fireplace.DOWNLOAD_CANCEL"
        private const val EX_URLS = "urls"
        private const val EX_SUBDIRS = "subdirs"
        private const val EX_NAMES = "names"
        private const val EX_SIZES = "sizes"

        fun startIntent(ctx: Context, files: List<DownloadFile>) =
            Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EX_URLS, files.map { it.url }.toTypedArray())
                putExtra(EX_SUBDIRS, files.map { it.subdir }.toTypedArray())
                putExtra(EX_NAMES, files.map { it.name }.toTypedArray())
                putExtra(EX_SIZES, files.map { it.sizeBytes }.toLongArray())
            }

        fun cancelIntent(ctx: Context) =
            Intent(ctx, DownloadService::class.java).apply { action = ACTION_CANCEL }
    }
}
