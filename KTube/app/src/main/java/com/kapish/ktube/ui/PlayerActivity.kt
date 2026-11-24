package com.kapish.ktube.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kapish.ktube.R
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.ItemType
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.formatCount
import com.kapish.ktube.databinding.ActivityPlayerBinding
import com.kapish.ktube.player.NowPlaying
import com.kapish.ktube.player.PlayerManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Full player: video (or artwork in audio mode), details, up next. Supports fullscreen + PiP. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var fullscreen = false
    private var upNext: List<Item> = emptyList()
    private var upNextQueueStart = 0
    private var queueCount = 0
    private lateinit var adapter: ItemAdapter

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.playerView.keepScreenOn = isPlaying && !PlayerManager.audioOnly
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        PlayerManager.ensureService()

        b.playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        b.playerView.setShowNextButton(true)
        b.playerView.setShowPreviousButton(true)
        sizePlayer()

        adapter = ItemAdapter(
            compact = true,
            onClick = { item, pos ->
                if (pos < queueCount) PlayerManager.playQueueIndex(upNextQueueStart + pos)
                else PlayerManager.play(upNext.drop(pos), 0, PlayerManager.audioOnly)
            },
            onMore = { item, v -> Actions.more(this, item, v, PlayerManager.audioOnly) },
        )
        b.upNext.layoutManager = LinearLayoutManager(this)
        b.upNext.adapter = adapter
        b.upNext.isNestedScrollingEnabled = false

        b.description.setOnClickListener {
            b.description.maxLines = if (b.description.maxLines == 3) Int.MAX_VALUE else 3
        }
        b.channel.setOnClickListener {
            val now = PlayerManager.now.value ?: return@setOnClickListener
            val url = now.item.uploaderUrl ?: return@setOnClickListener
            Actions.openList(this, Item(ItemType.CHANNEL, url, now.item.uploader ?: "Channel"), PlayerManager.audioOnly)
        }
        b.subscribe.setOnClickListener {
            val now = PlayerManager.now.value ?: return@setOnClickListener
            val url = now.item.uploaderUrl ?: return@setOnClickListener
            val ch = Item(ItemType.CHANNEL, url, now.item.uploader ?: "Channel",
                thumbnail = now.info?.uploaderAvatars?.let { Item.bestImage(it) })
            Library.toggleSubscribe(ch); renderButtons(now)
        }
        b.like.setOnClickListener {
            val now = PlayerManager.now.value ?: return@setOnClickListener
            Library.toggleLike(now.item); renderButtons(now)
        }
        b.audioOnly.setOnClickListener { PlayerManager.setAudioOnly(!PlayerManager.audioOnly) }
        b.quality.setOnClickListener { pickQuality() }
        b.share.setOnClickListener { PlayerManager.now.value?.let { Actions.share(this, it.item) } }
        b.autoplay.isChecked = Library.autoplay
        b.autoplay.setOnCheckedChangeListener { _, c -> Library.autoplay = c }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerManager.now.collect { render(it) } }
                launch { combine(PlayerManager.queue, PlayerManager.now) { q, n -> q to n }.collect { renderUpNext() } }
                launch { PlayerManager.loading.collect { b.loading.visibility = if (it) View.VISIBLE else View.GONE } }
                launch {
                    PlayerManager.error.collect {
                        b.error.text = it; b.error.visibility = if (it == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        b.playerView.player = PlayerManager.player
        PlayerManager.player.addListener(listener)
    }

    override fun onStop() {
        // Detach the surface only; playback continues in the background service.
        PlayerManager.player.removeListener(listener)
        b.playerView.player = null
        super.onStop()
    }

    private fun render(now: NowPlaying?) {
        if (now == null) { finish(); return }
        val info = now.info
        b.title.text = now.item.title
        b.meta.text = listOfNotNull(
            info?.viewCount?.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
            info?.textualUploadDate,
        ).joinToString(" · ")
        b.channel.text = now.item.uploader.orEmpty()
        b.channelSubs.text = info?.uploaderSubscriberCount?.takeIf { it >= 0 }?.let { "${formatCount(it)} subscribers" } ?: ""
        b.description.text = info?.description?.content?.let {
            androidx.core.text.HtmlCompat.fromHtml(it, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
        } ?: ""
        b.description.visibility = if (b.description.text.isNullOrBlank()) View.GONE else View.VISIBLE
        b.quality.text = when {
            now.audioOnly -> "Audio"
            now.height != null -> "${now.height}p"
            else -> "Quality"
        }
        renderButtons(now)
        b.playerView.keepScreenOn = PlayerManager.player.isPlaying && !now.audioOnly
    }

    private fun renderButtons(now: NowPlaying) {
        b.like.text = if (Library.isLiked(now.item.url)) "Liked" else "Like"
        b.like.setIconResource(if (Library.isLiked(now.item.url)) R.drawable.ic_like_filled else R.drawable.ic_like)
        val subbed = now.item.uploaderUrl?.let { Library.isSubscribed(it) } == true
        b.subscribe.text = getString(if (subbed) R.string.subscribed else R.string.subscribe)
        b.audioOnly.isChecked = now.audioOnly
    }

    private fun renderUpNext() {
        val q = PlayerManager.queue.value
        val qi = PlayerManager.queueIndex
        val queued = if (qi >= 0) q.drop(qi + 1) else emptyList()
        val known = q.map { it.url }.toHashSet()
        val related = PlayerManager.now.value?.info?.relatedItems.orEmpty()
            .mapNotNull { Item.from(it) }.filter { it.type == ItemType.STREAM && it.url !in known }
        upNextQueueStart = qi + 1
        queueCount = queued.size
        upNext = queued + related
        adapter.submitList(upNext)
    }

    private fun pickQuality() {
        val now = PlayerManager.now.value ?: return
        val options = now.heights.map { "${it}p" } + "Audio only (saves data)"
        MaterialAlertDialogBuilder(this)
            .setTitle("Quality")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == now.heights.size) PlayerManager.setAudioOnly(true)
                else PlayerManager.setQuality(now.heights[which])
            }
            .show()
    }

    // ------------------------------------------------------------- fullscreen + PiP

    private fun sizePlayer() {
        val lp = b.playerBox.layoutParams
        lp.height = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT
        else (resources.displayMetrics.widthPixels * 9 / 16)
        b.playerBox.layoutParams = lp
        b.details.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        sizePlayer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureModeCompat()) return
        fullscreen = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        sizePlayer()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = PlayerManager.player
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && p.isPlaying && !PlayerManager.audioOnly) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        b.playerView.useController = !isInPip
        if (isInPip) {
            b.details.visibility = View.GONE
            b.playerBox.layoutParams = b.playerBox.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        } else {
            fullscreen = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            sizePlayer()
        }
    }

    private fun isInPictureInPictureModeCompat() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
}
