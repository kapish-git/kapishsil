package com.kapish.ktube.data

import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

enum class ItemType { STREAM, CHANNEL, PLAYLIST }

/** UI-level item shown in lists and stored in the queue / library. */
data class Item(
    val type: ItemType,
    val url: String,
    val title: String,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val isLive: Boolean = false,
    val views: Long = -1,
    val uploaded: String? = null,
    val count: Long = -1,
    val uploadedMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("url", url).put("title", title)
        .put("uploader", uploader).put("uploaderUrl", uploaderUrl).put("thumbnail", thumbnail)
        .put("duration", durationSec).put("live", isLive)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            type = runCatching { ItemType.valueOf(o.optString("type")) }.getOrDefault(ItemType.STREAM),
            url = o.optString("url"),
            title = o.optString("title"),
            uploader = o.optString("uploader").takeIf { it.isNotBlank() && it != "null" },
            uploaderUrl = o.optString("uploaderUrl").takeIf { it.isNotBlank() && it != "null" },
            thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
            durationSec = o.optLong("duration", -1),
            isLive = o.optBoolean("live"),
        )

        fun from(info: InfoItem): Item? = when (info) {
            is StreamInfoItem -> Item(
                ItemType.STREAM, info.url, info.name, info.uploaderName, info.uploaderUrl,
                bestImage(info.thumbnails), info.duration,
                info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
                info.viewCount, info.textualUploadDate,
                uploadedMs = runCatching { info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() ?: 0,
            )
            is ChannelInfoItem -> Item(
                ItemType.CHANNEL, info.url, info.name, thumbnail = bestImage(info.thumbnails),
                count = info.subscriberCount,
            )
            is PlaylistInfoItem -> Item(
                ItemType.PLAYLIST, info.url, info.name, info.uploaderName, info.uploaderUrl,
                bestImage(info.thumbnails), count = info.streamCount,
            )
            else -> null
        }

        fun bestImage(images: List<Image>?): String? {
            if (images.isNullOrEmpty()) return null
            val sized = images.filter { it.height > 0 }
            if (sized.isEmpty()) return images.last().url
            return (sized.filter { it.height <= 720 }.maxByOrNull { it.height }
                ?: sized.minByOrNull { it.height })?.url
        }
    }
}

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}.replace(".0", "")
