package com.fireplace.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app streaming downloader for the one-time setup files (model + knowledge
 * pack). Uses INTERNET only here, at the user's explicit tap; inference and data
 * import never touch the network. Streams straight to the app's external files
 * dir - exactly where the engine and pack loader read from.
 *
 * The actual transfer is driven from [DownloadService], a foreground service, so
 * a large (0.5–11 GB) download survives the screen turning off or the user
 * switching apps instead of being killed with the process - the same resilience
 * the OS download manager gave us, but entirely in-app and offline-after-setup.
 * [DownloadBus] carries progress back to the UI.
 */
object Downloader {

    data class Progress(val doneBytes: Long, val totalBytes: Long, val label: String) {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    @Volatile private var cancelled = false

    /** Signal the running transfer to stop at the next chunk boundary. */
    fun cancel() { cancelled = true }

    /**
     * Stream [files] into [base], reporting aggregate [onProgress] and a terminal
     * [onComplete]. Runs on the calling thread (the service's worker thread).
     */
    fun run(
        base: File,
        files: List<DownloadFile>,
        onProgress: (Progress) -> Unit,
        onComplete: (ok: Boolean, error: String?) -> Unit,
    ) {
        cancelled = false
        val total = files.sumOf { it.sizeBytes }
        var doneBefore = 0L
        try {
            for (f in files) {
                val dest = File(base, if (f.subdir.isEmpty()) f.name else "${f.subdir}/${f.name}")
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part").apply { delete() }

                var conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
                }
                conn.connect()
                // Follow one extra redirect manually (HF often 302s to a CDN).
                if (conn.responseCode in 300..399) {
                    val loc = conn.getHeaderField("Location"); conn.disconnect()
                    conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
                    }
                    conn.connect()
                }
                if (conn.responseCode !in 200..299) throw IOException("server returned ${conn.responseCode}")

                var lastTick = 0L
                conn.inputStream.use { ins ->
                    tmp.outputStream().buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var fileDone = 0L
                        while (true) {
                            if (cancelled) { tmp.delete(); onComplete(false, "cancelled"); return }
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); fileDone += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 250) {  // throttle UI updates
                                lastTick = now
                                onProgress(Progress(doneBefore + fileDone, total, f.name))
                            }
                        }
                    }
                }
                conn.disconnect()
                dest.delete(); if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                doneBefore += f.sizeBytes
                onProgress(Progress(doneBefore, total, f.name))
            }
            onComplete(true, null)
        } catch (e: Exception) {
            onComplete(false, e.message ?: "download failed - check your connection")
        }
    }
}

/**
 * Process-wide relay between [DownloadService] (which runs the transfer) and the
 * ViewModel (which renders progress). The service lives in the same process, so a
 * plain singleton with volatile fields is the simplest reliable channel.
 */
object DownloadBus {
    @Volatile var active: Boolean = false; private set
    @Volatile var latest: Downloader.Progress? = null; private set

    @Volatile private var progressSink: ((Downloader.Progress) -> Unit)? = null
    @Volatile private var completeSink: ((Boolean, String?) -> Unit)? = null

    /** The UI subscribes; it immediately replays the latest known progress. */
    fun observe(onProgress: (Downloader.Progress) -> Unit, onComplete: (Boolean, String?) -> Unit) {
        progressSink = onProgress; completeSink = onComplete
        latest?.let(onProgress)
    }

    fun clearObservers() { progressSink = null; completeSink = null }

    fun onStarted(total: Long) { active = true; latest = Downloader.Progress(0, total, "") }
    fun onProgress(p: Downloader.Progress) { latest = p; progressSink?.invoke(p) }
    fun onComplete(ok: Boolean, err: String?) {
        active = false; latest = null; completeSink?.invoke(ok, err)
    }
}
