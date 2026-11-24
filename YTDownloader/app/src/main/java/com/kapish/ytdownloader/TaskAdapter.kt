package com.kapish.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kapish.ytdownloader.databinding.ItemTaskBinding

class TaskAdapter(
    private val onCancel: (DownloadTask) -> Unit,
    private val onRetry: (DownloadTask) -> Unit,
) : ListAdapter<DownloadTask, TaskAdapter.VH>(Diff) {

    class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        with(holder.b) {
            title.text = t.title
            quality.text = t.quality.label
            val active = t.status == Status.DOWNLOADING || t.status == Status.PROCESSING
            progress.visibility = if (active || t.status == Status.QUEUED) View.VISIBLE else View.GONE
            progress.isIndeterminate = t.status != Status.DOWNLOADING || t.progress <= 0f
            if (!progress.isIndeterminate) progress.setProgressCompat(t.progress.toInt(), true)
            status.text = when (t.status) {
                Status.QUEUED -> "Waiting…"
                Status.DOWNLOADING -> buildString {
                    append("%.1f%%".format(t.progress.coerceAtLeast(0f)))
                    if (t.etaSec > 0) append("  ·  ${formatDuration(t.etaSec)} left")
                }
                Status.PROCESSING -> t.message.ifBlank { "Processing…" }
                Status.DONE -> "✓ ${t.message}"
                Status.FAILED -> "✗ ${t.message}"
                Status.CANCELLED -> "Cancelled"
            }
            action.visibility = View.VISIBLE
            when (t.status) {
                Status.QUEUED, Status.DOWNLOADING, Status.PROCESSING -> {
                    action.setText(R.string.cancel); action.setOnClickListener { onCancel(t) }
                }
                Status.FAILED, Status.CANCELLED -> {
                    action.setText(R.string.retry); action.setOnClickListener { onRetry(t) }
                }
                Status.DONE -> action.visibility = View.GONE
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(a: DownloadTask, b: DownloadTask) = a.id == b.id
        override fun areContentsTheSame(a: DownloadTask, b: DownloadTask) = a == b
    }
}
