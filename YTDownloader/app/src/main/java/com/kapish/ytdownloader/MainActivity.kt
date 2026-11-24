package com.kapish.ytdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.kapish.ytdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val entryAdapter = EntryAdapter { updateSelectionText() }
    private var current: MediaInfo? = null
    private var selectedQuality: Quality? = null
    private var pendingDownload: (() -> Unit)? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingDownload?.invoke()
        else toast("Storage permission is needed to save files")
        pendingDownload = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_downloads -> { startActivity(Intent(this, DownloadsActivity::class.java)); true }
                R.id.action_update -> { updateYtdlp(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }

        b.entries.layoutManager = LinearLayoutManager(this)
        b.entries.adapter = entryAdapter

        b.urlLayout.setEndIconOnClickListener { pasteFromClipboard() }
        b.urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { fetch(); true } else false
        }
        b.urlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString().orEmpty()
                // A watch?v=...&list=... link: let the user choose video vs whole playlist.
                b.singleOnly.visibility =
                    if (t.contains("list=") && (t.contains("v=") || t.contains("youtu.be/"))) View.VISIBLE else View.GONE
                b.urlLayout.error = null
            }
        })
        b.fetchButton.setOnClickListener { fetch() }
        b.selectAll.setOnClickListener {
            val allSelected = entryAdapter.items.all { it.selected }
            entryAdapter.setAll(!allSelected)
        }
        b.quality.setOnItemClickListener { parent, _, pos, _ ->
            selectedQuality = parent.getItemAtPosition(pos) as Quality
        }
        b.downloadButton.setOnClickListener { startDownload() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch {
                    DownloadQueue.tasks.collect {
                        val n = DownloadQueue.activeCount()
                        b.toolbar.menu.findItem(R.id.action_downloads)?.title =
                            if (n > 0) getString(R.string.downloads) + " ($n)" else getString(R.string.downloads)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (savedInstanceState == null) handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        b.urlInput.setText(url)
        fetch()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = URL_REGEX.find(text)?.value
        if (url == null) toast("Clipboard has no link") else b.urlInput.setText(url)
    }

    private fun fetch() {
        val raw = b.urlInput.text?.toString()?.trim().orEmpty()
        val url = URL_REGEX.find(raw)?.value
        if (url == null) { b.urlLayout.error = "Enter a valid YouTube video or playlist link"; return }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(b.urlInput.windowToken, 0)
        vm.fetch(url, singleVideoOnly = b.singleOnly.visibility == View.VISIBLE && b.singleOnly.isChecked)
    }

    private fun render(s: UiState) {
        val loading = s is UiState.Loading
        b.progress.visibility = if (loading) View.VISIBLE else View.GONE
        b.fetchButton.isEnabled = !loading
        b.status.visibility = if (s is UiState.Loading || s is UiState.Error) View.VISIBLE else View.GONE
        when (s) {
            is UiState.Loading -> b.status.text = s.message
            is UiState.Error -> b.status.text = "⚠ ${s.message}"
            else -> Unit
        }
        if (s is UiState.Loaded && s.info !== current) showInfo(s.info)
        if (s !is UiState.Loaded) {
            current = null
            b.infoCard.visibility = View.GONE
            b.playlistHeader.visibility = View.GONE
            b.entries.visibility = View.INVISIBLE
            b.bottomBar.visibility = View.GONE
        }
    }

    private fun showInfo(info: MediaInfo) {
        current = info
        b.infoCard.visibility = View.VISIBLE
        b.bottomBar.visibility = View.VISIBLE
        b.title.text = info.title
        b.subtitle.text = listOfNotNull(
            info.uploader,
            if (info.isPlaylist) "${info.entries.size} videos" else formatDuration(info.durationSec).ifBlank { null },
        ).joinToString("  ·  ")
        b.thumb.load(info.thumbnail) { crossfade(true) }

        if (info.isPlaylist) {
            b.playlistHeader.visibility = View.VISIBLE
            b.entries.visibility = View.VISIBLE
            entryAdapter.items = info.entries
            updateSelectionText()
        } else {
            b.playlistHeader.visibility = View.GONE
            b.entries.visibility = View.INVISIBLE
            entryAdapter.items = emptyList()
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, info.qualities)
        b.quality.setAdapter(adapter)
        // Default: 720p if offered (good size/quality balance), else best.
        val default = info.qualities.firstOrNull { it.label == "720p" } ?: info.qualities.first()
        selectedQuality = default
        b.quality.setText(default.toString(), false)
    }

    private fun updateSelectionText() {
        val total = entryAdapter.items.size
        val sel = entryAdapter.items.count { it.selected }
        b.selectionText.text = "$sel of $total selected"
        b.selectAll.text = getString(if (sel == total) R.string.select_none else R.string.select_all)
        b.downloadButton.isEnabled = current?.isPlaylist != true || sel > 0
    }

    private fun startDownload() {
        val info = current ?: return
        val quality = selectedQuality ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload() }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val folder = if (info.isPlaylist) info.title else null
        val tasks = info.entries.filter { it.selected }.map {
            DownloadTask(UUID.randomUUID().toString(), it.url, it.title, quality, folder)
        }
        if (tasks.isEmpty()) { toast("Select at least one video"); return }
        DownloadQueue.enqueue(this, tasks)
        Snackbar.make(b.root, "${tasks.size} download(s) started · ${quality.label}", Snackbar.LENGTH_LONG)
            .setAction(R.string.view) { startActivity(Intent(this, DownloadsActivity::class.java)) }
            .show()
    }

    private fun updateYtdlp() {
        toast("Checking for yt-dlp updates…")
        vm.updateYtdlp { toast(it) }
    }

    private fun showAbout() {
        val v = runCatching { Ytdl.version(this) }.getOrNull() ?: "not initialised yet"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                "Version ${BuildConfig.VERSION_NAME}\nyt-dlp: $v\n\n" +
                    "Files are saved to Download/${Storage.ROOT}.\n\n" +
                    "Only download videos you own or have permission to download. " +
                    "Respect copyright and YouTube's Terms of Service."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val URL_REGEX = Regex("https?://\\S+")
    }
}
