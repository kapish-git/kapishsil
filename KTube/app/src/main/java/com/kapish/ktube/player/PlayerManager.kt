package com.kapish.ktube.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.ItemType
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

data class NowPlaying(
    val item: Item,
    val info: StreamInfo?,
    val heights: List<Int> = emptyList(),
    val height: Int? = null,
    val audioOnly: Boolean = false,
)

/**
 * Owns the single ExoPlayer instance and the play queue. Lives in the app process and is
 * shared by [PlaybackService] (background / lock-screen / notification) and the UI.
 */
@UnstableApi
object PlayerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context
    private var _player: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Whole queue; player playlist index i maps to queue[base + i]. */
    private val _queue = MutableStateFlow<List<Item>>(emptyList())
    val queue: StateFlow<List<Item>> = _queue
    private var base = 0

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Music mode = audio only by default, endless radio from related songs. */
    var audioOnly = false
        private set

    private val infoCache = HashMap<String, StreamInfo>()
    private var prefetchJob: Job? = null
    private var retriedUrl: String? = null

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    val player: ExoPlayer
        get() = _player ?: createPlayer().also { _player = it }

    val queueIndex: Int get() = _player?.let { base + it.currentMediaItemIndex } ?: -1

    private fun createPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)          // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)           // keep CPU + Wi-Fi awake while locked
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onCurrentChanged()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefetchNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                val now = _now.value ?: return
                // Stream URLs can expire / be rejected: re-extract once, then give up.
                if (retriedUrl != now.item.url) {
                    retriedUrl = now.item.url
                    infoCache.remove(now.item.url)
                    reloadCurrent(keepPosition = true)
                } else {
                    _error.value = "Playback failed: ${error.errorCodeName}"
                }
            }
        })
        return p
    }

    /** Keeps the MediaSessionService bound/started so playback survives backgrounding. */
    fun ensureService() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
    }

    fun onServiceDestroyed() {
        _player?.release()
        _player = null
        controllerFuture?.let { f -> runCatching { MediaController.releaseFuture(f) } }
        controllerFuture = null
        _now.value = null
        _queue.value = emptyList()
    }

    // ---------------------------------------------------------------- public controls

    fun play(items: List<Item>, index: Int, audioOnly: Boolean) {
        val streams = items.filter { it.type == ItemType.STREAM }
        if (streams.isEmpty()) return
        val start = streams.indexOf(items.getOrNull(index)).coerceAtLeast(0)
        ensureService()
        this.audioOnly = audioOnly
        _queue.value = streams
        base = start
        _error.value = null
        player.stop()
        player.clearMediaItems()
        val item = streams[start]
        _now.value = NowPlaying(item, null, audioOnly = audioOnly)
        loadInto(item, replaceAll = true, startPositionMs = 0)
    }

    fun playSingle(item: Item, audioOnly: Boolean) = play(listOf(item), 0, audioOnly)

    fun playQueueIndex(i: Int) = play(_queue.value, i, audioOnly)

    fun addToQueue(item: Item) {
        if (item.type != ItemType.STREAM) return
        if (_queue.value.isEmpty()) { playSingle(item, audioOnly); return }
        _queue.value = _queue.value + item
        prefetchNext()
    }

    fun playNext(item: Item) {
        if (item.type != ItemType.STREAM) return
        if (_queue.value.isEmpty()) { playSingle(item, audioOnly); return }
        val qi = queueIndex
        _queue.value = _queue.value.toMutableList().apply { add(qi + 1, item) }
        trimAfterCurrent()
        prefetchNext()
    }

    fun setQuality(height: Int) {
        Library.preferredHeight = height
        audioOnly = false
        reloadCurrent(keepPosition = true)
    }

    fun setAudioOnly(enabled: Boolean) {
        if (audioOnly == enabled) return
        audioOnly = enabled
        reloadCurrent(keepPosition = true)
    }

    fun stop() {
        player.stop(); player.clearMediaItems()
        _queue.value = emptyList(); _now.value = null
    }

    // ---------------------------------------------------------------- internals

    private fun onCurrentChanged() {
        val qi = queueIndex
        val item = _queue.value.getOrNull(qi) ?: return
        val info = infoCache[item.url]
        _now.value = buildNow(item, info)
        Library.addHistory(item)
        prefetchNext()
    }

    private fun buildNow(item: Item, info: StreamInfo?): NowPlaying {
        val heights = info?.let { availableHeights(it) } ?: emptyList()
        val h = if (audioOnly || heights.isEmpty()) null else pickHeight(heights, Library.preferredHeight)
        val enriched = info?.let {
            item.copy(
                title = it.name ?: item.title,
                uploader = it.uploaderName ?: item.uploader,
                uploaderUrl = it.uploaderUrl ?: item.uploaderUrl,
                thumbnail = item.thumbnail ?: Item.bestImage(it.thumbnails),
                durationSec = if (it.duration > 0) it.duration else item.durationSec,
            )
        } ?: item
        return NowPlaying(enriched, info, heights, h, audioOnly)
    }

    private fun trimAfterCurrent() {
        val p = player
        val cur = p.currentMediaItemIndex
        if (p.mediaItemCount > cur + 1) p.removeMediaItems(cur + 1, p.mediaItemCount)
    }

    private fun reloadCurrent(keepPosition: Boolean) {
        val item = _now.value?.item ?: return
        val p = player
        val pos = if (keepPosition) p.currentPosition else 0
        trimAfterCurrent()
        loadInto(item, replaceAll = false, startPositionMs = pos)
    }

    /** Resolves [item] and either replaces the whole playlist or the current entry. */
    private fun loadInto(item: Item, replaceAll: Boolean, startPositionMs: Long) {
        prefetchJob?.cancel()
        _loading.value = true
        scope.launch {
            try {
                val info = infoCache[item.url] ?: Repo.stream(item.url).also { infoCache[item.url] = it }
                val source = buildSource(info, item)
                val p = player
                if (replaceAll || p.mediaItemCount == 0) {
                    p.setMediaSource(source, startPositionMs)
                } else {
                    val idx = p.currentMediaItemIndex
                    p.addMediaSource(idx + 1, source)
                    p.removeMediaItem(idx)
                    p.seekTo(idx, startPositionMs)
                }
                p.prepare()
                p.playWhenReady = true
                _now.value = buildNow(item, info)
                if (replaceAll) Library.addHistory(item)
                prefetchNext()
            } catch (e: Exception) {
                _error.value = "Couldn't load: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Makes sure the item after the current one is resolved and in the player playlist. */
    private fun prefetchNext() {
        val p = _player ?: return
        if (prefetchJob?.isActive == true) return
        if (p.mediaItemCount == 0 || p.currentMediaItemIndex < p.mediaItemCount - 1) return
        prefetchJob = scope.launch {
            var attempts = 0
            while (attempts < 3) {
                val nextQ = base + p.mediaItemCount
                if (nextQ >= _queue.value.size && !extendWithRelated()) return@launch
                val next = _queue.value.getOrNull(nextQ) ?: return@launch
                try {
                    val info = infoCache[next.url] ?: Repo.stream(next.url).also { infoCache[next.url] = it }
                    p.addMediaSource(buildSource(info, next))
                    if (p.playbackState == Player.STATE_ENDED) { p.seekToNextMediaItem(); p.play() }
                    return@launch
                } catch (e: Exception) {
                    // Unplayable (private, age-restricted…): drop it and try the following one.
                    _queue.value = _queue.value.toMutableList().apply { removeAt(nextQ) }
                    attempts++
                }
            }
        }
    }

    /** Autoplay / radio: append related videos (or songs) of the current item. */
    private fun extendWithRelated(): Boolean {
        if (!Library.autoplay) return false
        val info = _now.value?.info ?: return false
        val known = _queue.value.map { it.url }.toHashSet()
        val related = info.relatedItems.mapNotNull { Item.from(it) }
            .filter { it.type == ItemType.STREAM && !it.isLive && it.url !in known }
            .take(10)
        if (related.isEmpty()) return false
        _queue.value = _queue.value + related
        return true
    }

    // ---------------------------------------------------------------- stream selection

    private fun usable(s: org.schabi.newpipe.extractor.stream.Stream) =
        s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun availableHeights(info: StreamInfo): List<Int> =
        (info.videoOnlyStreams.filter(::usable) + info.videoStreams.filter(::usable))
            .map { it.height }.filter { it > 0 }.distinct().sortedDescending()

    private fun pickHeight(heights: List<Int>, preferred: Int): Int =
        heights.filter { it <= preferred }.maxOrNull() ?: heights.min()

    private fun codecRank(v: VideoStream): Int {
        val c = v.codec.orEmpty()
        return when { c.startsWith("avc") -> 0; c.startsWith("vp9") || c.startsWith("vp09") -> 1; else -> 2 }
    }

    private fun bestAudio(info: StreamInfo): AudioStream? =
        info.audioStreams.filter(::usable)
            .sortedWith(
                compareBy<AudioStream> {
                    // Prefer the original language track over dubbed ones
                    when (it.audioTrackType) { null, AudioTrackType.ORIGINAL -> 0; else -> 1 }
                }.thenBy { if (it.format == MediaFormat.M4A) 0 else 1 }
                    .thenByDescending { it.averageBitrate }
            ).firstOrNull()

    /**
     * Stream URLs are bound to the IP that extracted them, so playback reuses the same OkHttp
     * client (same network / address family) and the client-specific User-Agent like NewPipe.
     */
    private fun dataSource(url: String): OkHttpDataSource.Factory {
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url))
            YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT)
        else com.kapish.ktube.net.OkHttpDownloader.USER_AGENT
        return OkHttpDataSource.Factory(com.kapish.ktube.net.OkHttpDownloader.instance.client)
            .setUserAgent(ua)
    }

    private fun mediaItem(uri: String, item: Item, info: StreamInfo): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(item.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.name ?: item.title)
                    .setArtist(info.uploaderName ?: item.uploader)
                    .setArtworkUri((item.thumbnail ?: Item.bestImage(info.thumbnails))?.let(Uri::parse))
                    .build()
            )
            .build()

    private fun buildSource(info: StreamInfo, item: Item): MediaSource {
        // Live streams: HLS / DASH manifests
        if (!info.hlsUrl.isNullOrBlank()) {
            return HlsMediaSource.Factory(dataSource(info.hlsUrl))
                .createMediaSource(mediaItem(info.hlsUrl, item, info))
        }
        val audio = bestAudio(info)
        if (audioOnly && audio != null) {
            return ProgressiveMediaSource.Factory(dataSource(audio.content))
                .createMediaSource(mediaItem(audio.content, item, info))
        }
        val heights = availableHeights(info)
        val target = if (heights.isEmpty()) 0 else pickHeight(heights, Library.preferredHeight)

        val videoOnly = info.videoOnlyStreams.filter { usable(it) && it.height == target }
            .minByOrNull(::codecRank)
        if (videoOnly != null && audio != null) {
            val v = ProgressiveMediaSource.Factory(dataSource(videoOnly.content))
                .createMediaSource(mediaItem(videoOnly.content, item, info))
            val a = ProgressiveMediaSource.Factory(dataSource(audio.content))
                .createMediaSource(MediaItem.fromUri(audio.content))
            return MergingMediaSource(true, v, a)
        }
        val muxed = info.videoStreams.filter(::usable).sortedByDescending { it.height }
            .let { l -> l.firstOrNull { it.height <= target.coerceAtLeast(1) } ?: l.lastOrNull() }
        if (muxed != null) {
            return ProgressiveMediaSource.Factory(dataSource(muxed.content))
                .createMediaSource(mediaItem(muxed.content, item, info))
        }
        if (audio != null) {
            return ProgressiveMediaSource.Factory(dataSource(audio.content))
                .createMediaSource(mediaItem(audio.content, item, info))
        }
        if (!info.dashMpdUrl.isNullOrBlank()) {
            return DashMediaSource.Factory(dataSource(info.dashMpdUrl))
                .createMediaSource(mediaItem(info.dashMpdUrl, item, info))
        }
        throw IllegalStateException("No playable stream found")
    }
}
