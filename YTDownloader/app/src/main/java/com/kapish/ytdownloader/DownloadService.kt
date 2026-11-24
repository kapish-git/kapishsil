package com.kapish.ytdownloader

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Foreground service that works through [DownloadQueue] one item at a time. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            DownloadQueue.cancelAll()
        }
        startInForeground(buildNotification("Preparing…", 0, true))
        if (worker?.isActive != true) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun runQueue() {
        acquireWakeLock()
        try {
            runCatching { Ytdl.ensureInit(this) }.onFailure { e ->
                DownloadQueue.tasks.value.filter { it.status == Status.QUEUED }.forEach { t ->
                    DownloadQueue.update(t.id) { it.copy(status = Status.FAILED, message = "Init failed: ${e.message}") }
                }
                return
            }
            while (true) {
                val task = DownloadQueue.nextQueued() ?: break
                process(task)
            }
        } finally {
            releaseWakeLock()
            showSummary()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun process(task: DownloadTask) {
        DownloadQueue.update(task.id) { it.copy(status = Status.DOWNLOADING, message = "Starting…") }
        notifyProgress(task.title, 0, true)
        val tmp = File(cacheDir, "dl/${task.id}")
        try {
            val file = Ytdl.download(this, task, tmp) { progress, eta, line ->
                val processing = line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                    line.startsWith("[FixupM") || line.startsWith("[Metadata]")
                DownloadQueue.update(task.id) {
                    if (it.status == Status.CANCELLED) it
                    else it.copy(
                        status = if (processing) Status.PROCESSING else Status.DOWNLOADING,
                        progress = if (progress >= 0) progress else it.progress,
                        etaSec = eta,
                        message = if (processing) "Processing…" else it.message,
                    )
                }
                notifyProgress(task.title, progress.toInt().coerceIn(0, 100), processing || progress < 0)
            }
            if (DownloadQueue.get(task.id)?.status == Status.CANCELLED) return
            DownloadQueue.update(task.id) { it.copy(status = Status.PROCESSING, message = "Saving…") }
            Storage.publish(this, file, task.folder)
            DownloadQueue.update(task.id) {
                it.copy(status = Status.DONE, progress = 100f, message = "Saved to Download/${Storage.ROOT}" +
                    (task.folder?.let { f -> "/$f" } ?: ""))
            }
        } catch (e: YoutubeDL.CanceledException) {
            DownloadQueue.update(task.id) { it.copy(status = Status.CANCELLED, message = "Cancelled") }
        } catch (e: Exception) {
            if (DownloadQueue.get(task.id)?.status != Status.CANCELLED) {
                DownloadQueue.update(task.id) { it.copy(status = Status.FAILED, message = friendlyError(e)) }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun notifyProgress(title: String, percent: Int, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700) return
        lastNotify = now
        val remaining = DownloadQueue.activeCount()
        val text = if (remaining > 1) "$title  ($remaining left)" else title
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text, percent, indeterminate))
        }
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel_all), cancel)
            .build()
    }

    private fun showSummary() {
        val list = DownloadQueue.tasks.value
        val done = list.count { it.status == Status.DONE }
        val failed = list.count { it.status == Status.FAILED }
        if (done + failed == 0) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.downloads_finished))
            .setContentText("$done saved" + if (failed > 0) ", $failed failed" else "")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(SUMMARY_ID, n) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTDownloader:download").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val SUMMARY_ID = 1002
        const val ACTION_CANCEL_ALL = "cancel_all"

        fun friendlyError(e: Throwable): String {
            val msg = e.message.orEmpty()
            val errLine = msg.lineSequence().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            return when {
                msg.contains("Sign in to confirm", true) -> "YouTube asked to sign in / verify. Try again later or update yt-dlp."
                msg.contains("Private video", true) -> "This video is private."
                msg.contains("Video unavailable", true) -> "Video unavailable."
                msg.contains("Unable to resolve host", true) || msg.contains("Network is unreachable", true) ->
                    "No internet connection."
                !errLine.isNullOrBlank() -> errLine.take(300)
                else -> msg.lineSequence().firstOrNull { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName
            }
        }
    }
}
