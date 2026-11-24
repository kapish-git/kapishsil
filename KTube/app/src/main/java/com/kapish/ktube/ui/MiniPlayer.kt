package com.kapish.ktube.ui

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import coil.load
import coil.transform.RoundedCornersTransformation
import com.kapish.ktube.R
import com.kapish.ktube.databinding.MiniPlayerBinding
import com.kapish.ktube.player.PlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bottom "now playing" bar, like the YT / YT Music mini player. */
class MiniPlayer(private val activity: AppCompatActivity, private val b: MiniPlayerBinding) {

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcon()
        override fun onPlaybackStateChanged(playbackState: Int) = updatePlayIcon()
    }

    init {
        b.root.setOnClickListener { activity.startActivity(Intent(activity, PlayerActivity::class.java)) }
        b.playPause.setOnClickListener {
            val p = PlayerManager.player
            if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
        }
        b.next.setOnClickListener { PlayerManager.player.seekToNextMediaItem() }
        b.close.setOnClickListener { PlayerManager.stop() }

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { PlayerManager.player.addListener(listener); updatePlayIcon() }
            override fun onStop(owner: LifecycleOwner) { PlayerManager.player.removeListener(listener) }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PlayerManager.now.collect { now ->
                        b.root.visibility = if (now == null) View.GONE else View.VISIBLE
                        if (now != null) {
                            b.title.text = now.item.title
                            b.artist.text = now.item.uploader.orEmpty()
                            b.thumb.load(now.item.thumbnail) { transformations(RoundedCornersTransformation(8f)) }
                        }
                    }
                }
                launch {
                    while (true) {
                        val p = PlayerManager.player
                        val d = p.duration
                        b.progress.setProgressCompat(
                            if (d > 0) (p.currentPosition * 1000 / d).toInt() else 0, false
                        )
                        delay(500)
                    }
                }
            }
        }
    }

    private fun updatePlayIcon() {
        b.playPause.setImageResource(if (PlayerManager.player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }
}
