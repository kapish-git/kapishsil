package com.kapish.ytdownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        // Unpack yt-dlp / python / ffmpeg in the background, then keep yt-dlp fresh
        // (YouTube changes often; an outdated yt-dlp is the #1 cause of failures).
        appScope.launch {
            runCatching { Ytdl.ensureInit(this@App) }
            runCatching { Ytdl.autoUpdateIfDue(this@App) }
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}
