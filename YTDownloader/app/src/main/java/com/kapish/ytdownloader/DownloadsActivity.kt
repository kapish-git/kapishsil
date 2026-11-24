package com.kapish.ytdownloader

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.kapish.ytdownloader.databinding.ActivityDownloadsBinding
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDownloadsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_clear -> { DownloadQueue.clearFinished(); true }
                R.id.action_cancel_all -> { DownloadQueue.cancelAll(); true }
                else -> false
            }
        }
        val adapter = TaskAdapter(
            onCancel = { DownloadQueue.cancel(it.id) },
            onRetry = { DownloadQueue.retry(this, it.id) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.itemAnimator = null
        b.list.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadQueue.tasks.collect { list ->
                    adapter.submitList(list)
                    b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    val done = list.count { it.status == Status.DONE }
                    b.toolbar.subtitle = if (list.isEmpty()) null else "$done of ${list.size} done"
                }
            }
        }
    }
}
