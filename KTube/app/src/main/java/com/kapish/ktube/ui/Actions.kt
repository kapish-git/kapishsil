package com.kapish.ktube.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.ItemType
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.Repo
import com.kapish.ktube.player.PlayerManager

/** Shared navigation + item actions. */
object Actions {

    /** Opens an item: plays streams, opens channel / playlist pages. */
    fun open(a: Activity, list: List<Item>, index: Int, music: Boolean) {
        val item = list.getOrNull(index) ?: return
        when (item.type) {
            ItemType.STREAM -> {
                PlayerManager.play(list, index, audioOnly = music)
                // Music plays in the mini player like YT Music; videos open the player screen.
                if (!music) a.startActivity(Intent(a, PlayerActivity::class.java))
            }
            ItemType.CHANNEL, ItemType.PLAYLIST -> openList(a, item, music)
        }
    }

    fun openList(a: Activity, item: Item, music: Boolean) {
        a.startActivity(
            Intent(a, ListActivity::class.java)
                .putExtra(ListActivity.EXTRA_URL, item.url)
                .putExtra(ListActivity.EXTRA_TYPE, item.type.name)
                .putExtra(ListActivity.EXTRA_TITLE, item.title)
                .putExtra(ListActivity.EXTRA_THUMB, item.thumbnail)
                .putExtra(ListActivity.EXTRA_MUSIC, music)
        )
    }

    fun openUrl(a: Activity, url: String) {
        val music = url.contains("music.youtube.com")
        when (Repo.linkType(url)) {
            ItemType.STREAM -> open(a, listOf(Item(ItemType.STREAM, url, "Loading…")), 0, music)
            ItemType.PLAYLIST -> openList(a, Item(ItemType.PLAYLIST, url, "Playlist"), music)
            ItemType.CHANNEL -> openList(a, Item(ItemType.CHANNEL, url, "Channel"), music)
            null -> Toast.makeText(a, "Unsupported link", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(a: Activity, item: Item) {
        a.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, item.url).putExtra(Intent.EXTRA_SUBJECT, item.title),
            "Share"
        ))
    }

    fun more(a: Activity, item: Item, anchor: View, music: Boolean) {
        val menu = PopupMenu(a, anchor)
        if (item.type == ItemType.STREAM) {
            menu.menu.add(0, 1, 0, "Play next")
            menu.menu.add(0, 2, 1, "Add to queue")
            menu.menu.add(0, 3, 2, if (music) "Play as video" else "Play as audio only")
            menu.menu.add(0, 4, 3, if (Library.isLiked(item.url)) "Remove from Liked" else "Save to Liked")
            if (item.uploaderUrl != null) menu.menu.add(0, 5, 4, "Go to channel")
        } else if (item.type == ItemType.CHANNEL) {
            menu.menu.add(0, 6, 0, if (Library.isSubscribed(item.url)) "Unsubscribe" else "Subscribe")
        }
        menu.menu.add(0, 7, 9, "Share")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { PlayerManager.playNext(item); toast(a, "Will play next") }
                2 -> { PlayerManager.addToQueue(item); toast(a, "Added to queue") }
                3 -> open(a, listOf(item), 0, music = !music)
                4 -> toast(a, if (Library.toggleLike(item)) "Saved to Liked" else "Removed from Liked")
                5 -> openList(a, Item(ItemType.CHANNEL, item.uploaderUrl!!, item.uploader ?: "Channel"), music)
                6 -> toast(a, if (Library.toggleSubscribe(item)) "Subscribed" else "Unsubscribed")
                7 -> share(a, item)
            }
            true
        }
        menu.show()
    }

    fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}
