package com.kapish.ktube.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.kapish.ktube.R
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.ItemType
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.Repo
import com.kapish.ktube.data.formatCount
import com.kapish.ktube.databinding.ActivityListBinding
import com.kapish.ktube.player.PlayerManager

/** Channel / playlist / album page. */
class ListActivity : AppCompatActivity() {
    private lateinit var b: ActivityListBinding
    private lateinit var list: PagedList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        MiniPlayer(this, b.mini)
        b.toolbar.setNavigationOnClickListener { finish() }

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val type = runCatching { ItemType.valueOf(intent.getStringExtra(EXTRA_TYPE)!!) }.getOrDefault(ItemType.PLAYLIST)
        val music = intent.getBooleanExtra(EXTRA_MUSIC, false)
        var thumb = intent.getStringExtra(EXTRA_THUMB)
        b.toolbar.title = intent.getStringExtra(EXTRA_TITLE)
        b.name.text = intent.getStringExtra(EXTRA_TITLE)

        val adapter = ItemAdapter(
            compact = music,
            onClick = { _, pos -> Actions.open(this, list.items, pos, music) },
            onMore = { item, v -> Actions.more(this, item, v, music) },
        )
        list = PagedList(this, b.list, adapter, b.swipe, b.status)

        b.playAll.setOnClickListener { PlayerManager.play(list.items, 0, music); if (!music) openPlayer() }
        b.shuffle.setOnClickListener {
            PlayerManager.play(list.items.filter { it.type == ItemType.STREAM }.shuffled(), 0, music)
            if (!music) openPlayer()
        }
        b.audio.setOnClickListener { PlayerManager.play(list.items, 0, audioOnly = true) }

        if (type == ItemType.CHANNEL) {
            fun renderSub() {
                b.subscribe.text = getString(if (Library.isSubscribed(url)) R.string.subscribed else R.string.subscribe)
            }
            b.subscribe.visibility = View.VISIBLE
            renderSub()
            b.subscribe.setOnClickListener {
                Library.toggleSubscribe(Item(ItemType.CHANNEL, url, b.name.text.toString(), thumbnail = thumb))
                renderSub()
            }
            b.avatar.load(thumb) { transformations(CircleCropTransformation()) }
            list.load {
                val page = Repo.channel(url)
                runOnUiThread {
                    b.name.text = page.info.name
                    b.toolbar.title = page.info.name
                    b.info.text = page.info.subscriberCount.takeIf { it >= 0 }?.let { "${formatCount(it)} subscribers" } ?: ""
                    thumb = Item.bestImage(page.info.avatars) ?: thumb
                    b.avatar.load(thumb) { transformations(CircleCropTransformation()) }
                }
                page.videos
            }
        } else {
            b.avatar.load(thumb)
            list.onLoaded = { p ->
                p.title?.let { b.name.text = it; b.toolbar.title = it }
                b.info.text = "${list.items.size}+ items"
                if (thumb == null) b.avatar.load(list.items.firstOrNull()?.thumbnail)
            }
            list.load { Repo.playlist(url) }
        }
    }

    private fun openPlayer() = startActivity(android.content.Intent(this, PlayerActivity::class.java))

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_MUSIC = "music"
    }
}
