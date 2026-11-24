package com.kapish.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/** Moves finished files into the public Download/YTDownloader folder. */
object Storage {
    const val ROOT = "YTDownloader"

    fun publish(ctx: Context, file: File, folder: String?): Uri? {
        val sub = folder?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, ROOT, sub).joinToString("/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            file.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            var dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)
            if (sub != null) dir = File(dir, sub)
            dir.mkdirs()
            var target = File(dir, file.name)
            var n = 1
            while (target.exists()) target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
            file.copyTo(target)
            file.delete()
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
            Uri.fromFile(target)
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)
}
