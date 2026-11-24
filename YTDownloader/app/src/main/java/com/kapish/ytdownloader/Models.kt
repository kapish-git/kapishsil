package com.kapish.ytdownloader

/** A selectable download quality and the yt-dlp options that produce it. */
data class Quality(
    val label: String,
    val detail: String,
    val args: List<Pair<String, String?>>,
    val isAudio: Boolean = false,
) {
    override fun toString() = if (detail.isBlank()) label else "$label  ·  $detail"

    companion object {
        private const val SORT_TAIL = "vcodec:h264,acodec:aac"

        fun best(detail: String = "") = Quality(
            "Best available", detail,
            listOf("-f" to "bv*+ba/b", "-S" to "res,$SORT_TAIL", "--merge-output-format" to "mp4")
        )

        fun video(height: Int, detail: String = "") = Quality(
            "${height}p", detail,
            listOf(
                "-f" to "bv*+ba/b",
                // Prefer the largest resolution <= height (falls back gracefully if missing)
                "-S" to "res:$height,$SORT_TAIL",
                "--merge-output-format" to "mp4",
            )
        )

        fun mp3(detail: String = "") = Quality(
            "Audio only (MP3)", detail,
            listOf(
                "-f" to "ba/b", "-x" to null,
                "--audio-format" to "mp3", "--audio-quality" to "0",
            ),
            isAudio = true
        )

        fun m4a(detail: String = "") = Quality(
            "Audio only (M4A)", detail,
            listOf("-f" to "ba[ext=m4a]/ba/b", "-x" to null, "--audio-format" to "m4a"),
            isAudio = true
        )

        /** Generic list used for playlists, where each video may differ. */
        fun genericList(): List<Quality> =
            listOf(best()) + listOf(2160, 1440, 1080, 720, 480, 360, 240, 144).map { video(it) } +
                listOf(mp3(), m4a())
    }
}

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Long?,
    var selected: Boolean = true,
)

data class MediaInfo(
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long?,
    val isPlaylist: Boolean,
    val url: String,
    val entries: List<PlaylistEntry>,
    val qualities: List<Quality>,
)

enum class Status { QUEUED, DOWNLOADING, PROCESSING, DONE, FAILED, CANCELLED }

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val quality: Quality,
    val folder: String?,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = -1,
    val message: String = "",
)

fun formatDuration(sec: Long?): String {
    if (sec == null || sec <= 0) return ""
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%d KB".format(bytes / 1024)
}
