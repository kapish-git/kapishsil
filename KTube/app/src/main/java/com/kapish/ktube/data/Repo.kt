package com.kapish.ktube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

/** A page of results plus a function that loads the next one (null when finished). */
class Paged(val title: String?, val items: List<Item>, val next: (suspend () -> Paged)?)

object Repo {
    val yt get() = ServiceList.YouTube

    // Search filters
    const val ALL = "all"
    const val VIDEOS = "videos"
    const val CHANNELS = "channels"
    const val PLAYLISTS = "playlists"
    const val SONGS = "music_songs"
    const val MUSIC_VIDEOS = "music_videos"
    const val ALBUMS = "music_albums"
    const val MUSIC_PLAYLISTS = "music_playlists"
    const val ARTISTS = "music_artists"

    private fun List<InfoItem>.toItems() = mapNotNull { Item.from(it) }

    suspend fun search(query: String, filter: String): Paged = withContext(Dispatchers.IO) {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
        val info = SearchInfo.getInfo(yt, handler)
        Paged(null, info.relatedItems.toItems(), info.nextPage?.let { searchMore(handler, it) })
    }

    private fun searchMore(handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler, page: Page):
        suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = SearchInfo.getMoreItems(yt, handler, page)
            Paged(null, p.items.toItems(), p.nextPage?.takeIf { p.hasNextPage() }?.let { searchMore(handler, it) })
        }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /** Public feeds, in display order. ("Trending" is skipped: YouTube removed that page.) */
    fun kioskIds(): List<String> = runCatching {
        val order = listOf("live", "trending_gaming", "trending_podcasts_episodes", "trending_music", "trending_movies_and_shows")
        val available = yt.kioskList.availableKiosks.filter { it != "Trending" }
        available.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    }.getOrDefault(emptyList())

    /** YT Music landing page: trending music, falling back to a popular-songs search. */
    suspend fun musicHome(): Paged {
        val kiosk = kioskIds().firstOrNull { it.contains("music") }
        if (kiosk != null) runCatching { kiosk(kiosk) }.getOrNull()?.takeIf { it.items.isNotEmpty() }?.let { return it }
        return search("top hits", MUSIC_VIDEOS)
    }

    /** "For you": videos related to what was watched recently (on-device recommendations). */
    suspend fun forYou(history: List<Item>): Paged = kotlinx.coroutines.coroutineScope {
        val seeds = history.filter { it.type == ItemType.STREAM }.take(5)
        val watched = history.map { it.url }.toHashSet()
        val lists = seeds.map { s ->
            async(Dispatchers.IO) {
                runCatching { stream(s.url).relatedItems.toItems() }.getOrDefault(emptyList())
                    .filter { it.type == ItemType.STREAM && it.url !in watched }
            }
        }.map { it.await() }
        // Interleave so each seed contributes near the top
        val out = LinkedHashMap<String, Item>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out.putIfAbsent(it.url, it) }
        Paged("For you", out.values.toList(), null)
    }

    fun kioskLabel(id: String) = when (id) {
        "Trending" -> "Trending"
        "live" -> "Live"
        "trending_music" -> "Music"
        "trending_gaming" -> "Gaming"
        "trending_podcasts_episodes" -> "Podcasts"
        "trending_movies_and_shows" -> "Trailers"
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    suspend fun kiosk(id: String): Paged = withContext(Dispatchers.IO) {
        val ex = yt.kioskList.getExtractorById(id, null)
        ex.fetchPage()
        val info = KioskInfo.getInfo(ex)
        Paged(info.name, info.relatedItems.toItems(), info.nextPage?.let { kioskMore(info.url, it) })
    }

    private fun kioskMore(url: String, page: Page): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = KioskInfo.getMoreItems(yt, url, page)
            Paged(null, p.items.toItems(), p.nextPage?.takeIf { p.hasNextPage() }?.let { kioskMore(url, it) })
        }
    }

    suspend fun playlist(url: String): Paged = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(yt, url)
        Paged(info.name, info.relatedItems.toItems(), info.nextPage?.let { playlistMore(url, it) })
    }

    private fun playlistMore(url: String, page: Page): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = PlaylistInfo.getMoreItems(yt, url, page)
            Paged(null, p.items.toItems(), p.nextPage?.takeIf { p.hasNextPage() }?.let { playlistMore(url, it) })
        }
    }

    class ChannelPage(val info: ChannelInfo, val videos: Paged)

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(yt, url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: info.tabs.firstOrNull()
        val videos = if (tab == null) Paged(info.name, emptyList(), null) else {
            val t = ChannelTabInfo.getInfo(yt, tab)
            Paged(info.name, t.relatedItems.toItems(), t.nextPage?.let { channelMore(tab, it) })
        }
        ChannelPage(info, videos)
    }

    private fun channelMore(tab: ListLinkHandler, page: Page): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            Paged(null, p.items.toItems(), p.nextPage?.takeIf { p.hasNextPage() }?.let { channelMore(tab, it) })
        }
    }

    suspend fun stream(url: String): StreamInfo = withContext(Dispatchers.IO) { StreamInfo.getInfo(yt, url) }

    /** Classifies a pasted/shared link. */
    fun linkType(url: String): ItemType? = runCatching {
        when {
            yt.streamLHFactory.acceptUrl(url) -> ItemType.STREAM
            yt.playlistLHFactory.acceptUrl(url) -> ItemType.PLAYLIST
            yt.channelLHFactory.acceptUrl(url) -> ItemType.CHANNEL
            else -> null
        }
    }.getOrNull()
}

/** Subscription feed: latest uploads of locally-subscribed channels, newest first. */
suspend fun Repo.subscriptionFeed(channels: List<Item>): List<Item> = kotlinx.coroutines.coroutineScope {
    channels.take(30).map { ch ->
        async(Dispatchers.IO) { runCatching { channel(ch.url).videos.items.take(8) }.getOrDefault(emptyList()) }
    }.flatMap { it.await() }
        .distinctBy { it.url }
        .sortedByDescending { it.uploadedMs }
}
