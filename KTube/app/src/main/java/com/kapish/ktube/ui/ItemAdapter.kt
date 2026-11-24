package com.kapish.ktube.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.kapish.ktube.R
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.ItemType
import com.kapish.ktube.data.formatCount
import com.kapish.ktube.data.formatDuration
import com.kapish.ktube.databinding.ItemRowBinding
import com.kapish.ktube.databinding.ItemVideoBinding

/**
 * Shows videos as big YouTube-style cards (or compact rows for music / queues),
 * channels with round avatars and playlists with a count.
 */
class ItemAdapter(
    private val compact: Boolean = false,
    private val onClick: (Item, Int) -> Unit,
    private val onMore: ((Item, View) -> Unit)? = null,
) : ListAdapter<Item, RecyclerView.ViewHolder>(Diff) {

    var highlightUrl: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    class Card(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)
    class Row(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) =
        if (!compact && getItem(position).type == ItemType.STREAM) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) Card(ItemVideoBinding.inflate(inf, parent, false))
        else Row(ItemRowBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is Card -> with(holder.b) {
                thumb.load(item.thumbnail) { crossfade(true); placeholder(R.color.thumb_placeholder) }
                duration.text = if (item.isLive) "LIVE" else formatDuration(item.durationSec)
                duration.visibility = if (duration.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                duration.setBackgroundResource(if (item.isLive) R.drawable.badge_live else R.drawable.badge)
                title.text = item.title
                meta.text = subtitle(item)
                root.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }
                more.visibility = if (onMore == null) View.GONE else View.VISIBLE
                more.setOnClickListener { onMore?.invoke(item, it) }
            }
            is Row -> with(holder.b) {
                if (item.type == ItemType.CHANNEL) {
                    thumb.load(item.thumbnail) { crossfade(true); transformations(CircleCropTransformation()) }
                } else {
                    thumb.load(item.thumbnail) {
                        crossfade(true); placeholder(R.color.thumb_placeholder)
                        transformations(RoundedCornersTransformation(12f))
                    }
                }
                title.text = item.title
                meta.text = subtitle(item)
                val playing = item.url == highlightUrl
                title.setTextColor(
                    if (playing) root.context.getColor(R.color.brand)
                    else com.google.android.material.color.MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface)
                )
                root.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }
                more.visibility = if (onMore == null) View.GONE else View.VISIBLE
                more.setOnClickListener { onMore?.invoke(item, it) }
            }
        }
    }

    private fun subtitle(item: Item): String = when (item.type) {
        ItemType.CHANNEL -> listOfNotNull(
            "Channel",
            item.count.takeIf { it >= 0 }?.let { "${formatCount(it)} subscribers" },
        ).joinToString(" · ")
        ItemType.PLAYLIST -> listOfNotNull(
            item.uploader, item.count.takeIf { it >= 0 }?.let { "$it videos" } ?: "Playlist",
        ).joinToString(" · ")
        ItemType.STREAM -> listOfNotNull(
            item.uploader,
            if (compact) formatDuration(item.durationSec).ifBlank { null } else null,
            item.views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
            item.uploaded?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(a: Item, b: Item) = a.url == b.url && a.type == b.type
        override fun areContentsTheSame(a: Item, b: Item) = a == b
    }
}
