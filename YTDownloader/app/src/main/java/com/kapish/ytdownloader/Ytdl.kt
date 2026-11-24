package com.kapish.ytdownloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Ytdl {
    @Volatile private var initialized = false
    private val lock = Any()
    private const val PREFS = "ytdl"
    private const val KEY_LAST_UPDATE = "last_update"

    fun ensureInit(ctx: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            initialized = true
        }
    }

    fun version(ctx: Context): String? = YoutubeDL.getInstance().versionName(ctx)

    /** Updates yt-dlp at most once a day. */
    fun autoUpdateIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0)
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return
        update(ctx)
    }

    fun update(ctx: Context): YoutubeDL.UpdateStatus? {
        ensureInit(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        return status
    }

    /** Reads metadata for a video or playlist URL. Blocking — call from a background thread. */
    fun fetch(ctx: Context, url: String, singleVideoOnly: Boolean): MediaInfo {
        ensureInit(ctx)
        val req = YoutubeDLRequest(url)
            .addOption("-J")
            .addOption("--flat-playlist")
            .addOption(if (singleVideoOnly) "--no-playlist" else "--yes-playlist")
        val resp = YoutubeDL.getInstance().execute(req, null, null)
        val json = JSONObject(resp.out.trim().lineSequence().last { it.startsWith("{") })

        val type = json.optString("_type", "video")
        return if (type == "playlist") parsePlaylist(json, url) else parseVideo(json, url)
    }

    private fun parsePlaylist(json: JSONObject, url: String): MediaInfo {
        val arr = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<PlaylistEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("id")
            val title = e.optStringOrNull("title") ?: id
            val entryUrl = e.optStringOrNull("url")?.takeIf { it.startsWith("http") }
                ?: e.optStringOrNull("webpage_url")
                ?: "https://www.youtube.com/watch?v=$id"
            val unavailable = title.equals("[Private video]", true) || title.equals("[Deleted video]", true)
            entries += PlaylistEntry(id, title, entryUrl, e.optLongOrNull("duration"), selected = !unavailable)
        }
        val thumb = json.optJSONArray("thumbnails")?.let { lastThumb(it) }
            ?: arr.optJSONObject(0)?.optJSONArray("thumbnails")?.let { lastThumb(it) }
        return MediaInfo(
            title = json.optStringOrNull("title") ?: "Playlist",
            uploader = json.optStringOrNull("uploader") ?: json.optStringOrNull("channel"),
            thumbnail = thumb,
            durationSec = null,
            isPlaylist = true,
            url = url,
            entries = entries,
            qualities = Quality.genericList(),
        )
    }

    private fun parseVideo(json: JSONObject, url: String): MediaInfo {
        val formats = json.optJSONArray("formats") ?: JSONArray()
        val duration = json.optLongOrNull("duration")

        // Best audio size (preferring m4a) used to estimate merged size.
        var bestAudio = 0L
        var bestAudioAbr = -1.0
        // height -> best estimated video-stream size (prefer h264)
        val byHeight = sortedMapOf<Int, Long>(reverseOrder())
        val h264Heights = HashSet<Int>()

        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val size = sizeOf(f, duration)
            if (vcodec == "none" && acodec != "none") {
                val abr = f.optDouble("abr", 0.0)
                val isM4a = f.optString("ext") == "m4a"
                if (isM4a && abr > bestAudioAbr) { bestAudioAbr = abr; bestAudio = size }
                else if (bestAudioAbr < 0 && size > bestAudio) bestAudio = size
            } else if (vcodec != "none") {
                val h = f.optInt("height", 0)
                if (h <= 0) continue
                val isH264 = vcodec.startsWith("avc")
                val cur = byHeight[h]
                when {
                    cur == null -> { byHeight[h] = size; if (isH264) h264Heights += h }
                    isH264 && h !in h264Heights -> { byHeight[h] = size; h264Heights += h }
                    isH264 == (h in h264Heights) && size > cur -> byHeight[h] = size
                }
            }
        }

        val qualities = ArrayList<Quality>()
        val topSize = byHeight.values.firstOrNull()?.let { it + bestAudio } ?: 0L
        qualities += Quality.best(sizeLabel(topSize))
        // Collapse odd heights (e.g. 1080 vs 1072) by keeping each one listed.
        for ((h, s) in byHeight) qualities += Quality.video(h, sizeLabel(s + bestAudio))
        qualities += Quality.mp3(sizeLabel(bestAudio))
        qualities += Quality.m4a(sizeLabel(bestAudio))

        val title = json.optStringOrNull("title") ?: "Video"
        return MediaInfo(
            title = title,
            uploader = json.optStringOrNull("uploader") ?: json.optStringOrNull("channel"),
            thumbnail = json.optStringOrNull("thumbnail"),
            durationSec = duration,
            isPlaylist = false,
            url = json.optStringOrNull("webpage_url") ?: url,
            entries = listOf(PlaylistEntry(json.optString("id"), title, json.optStringOrNull("webpage_url") ?: url, duration)),
            qualities = qualities,
        )
    }

    /**
     * Downloads one item into [outDir]. Returns the produced file.
     * [onProgress] receives (percent 0..100, eta seconds, raw output line).
     */
    fun download(
        ctx: Context,
        task: DownloadTask,
        outDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        ensureInit(ctx)
        outDir.mkdirs()
        val req = YoutubeDLRequest(task.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--embed-metadata")
            .addOption("--newline")
            .addOption("-o", File(outDir, "%(title).120B.%(ext)s").absolutePath)
        for ((k, v) in task.quality.args) if (v == null) req.addOption(k) else req.addOption(k, v)

        YoutubeDL.getInstance().execute(req, task.id, onProgress)

        return outDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Download finished but no output file was found")
    }

    fun cancel(id: String) = YoutubeDL.getInstance().destroyProcessById(id)

    private fun sizeOf(f: JSONObject, duration: Long?): Long {
        f.optLongOrNull("filesize")?.let { if (it > 0) return it }
        f.optLongOrNull("filesize_approx")?.let { if (it > 0) return it }
        val tbr = f.optDouble("tbr", 0.0)
        if (tbr > 0 && duration != null) return (tbr * 1000 / 8 * duration).toLong()
        return 0
    }

    private fun sizeLabel(bytes: Long) = if (bytes > 0) "~${formatSize(bytes)}" else ""

    private fun lastThumb(arr: JSONArray): String? =
        if (arr.length() == 0) null else arr.optJSONObject(arr.length() - 1)?.optStringOrNull("url")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toLong()
}
