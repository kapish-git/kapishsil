package com.kapish.ytdownloader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory download queue shared by the UI and [DownloadService]. */
object DownloadQueue {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    fun enqueue(ctx: Context, newTasks: List<DownloadTask>) {
        if (newTasks.isEmpty()) return
        _tasks.update { it + newTasks }
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun get(id: String) = _tasks.value.firstOrNull { it.id == id }

    fun nextQueued(): DownloadTask? = _tasks.value.firstOrNull { it.status == Status.QUEUED }

    fun cancel(id: String) {
        val t = get(id) ?: return
        when (t.status) {
            Status.QUEUED -> update(id) { it.copy(status = Status.CANCELLED, message = "Cancelled") }
            Status.DOWNLOADING, Status.PROCESSING -> {
                update(id) { it.copy(status = Status.CANCELLED, message = "Cancelled") }
                Ytdl.cancel(id)
            }
            else -> Unit
        }
    }

    fun retry(ctx: Context, id: String) {
        val t = get(id) ?: return
        if (t.status == Status.FAILED || t.status == Status.CANCELLED) {
            update(id) { it.copy(status = Status.QUEUED, progress = 0f, etaSec = -1, message = "") }
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }
    }

    fun cancelAll() = _tasks.value.forEach { cancel(it.id) }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.status == Status.QUEUED || it.status == Status.DOWNLOADING || it.status == Status.PROCESSING } }
    }

    fun activeCount() = _tasks.value.count {
        it.status == Status.QUEUED || it.status == Status.DOWNLOADING || it.status == Status.PROCESSING
    }
}
