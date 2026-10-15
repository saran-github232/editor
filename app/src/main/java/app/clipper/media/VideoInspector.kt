package app.clipper.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.clipper.model.ClipPlanner
import app.clipper.model.VideoInfo

object VideoInspector {
    fun inspect(context: Context, uri: Uri): VideoInfo {
        require(uri.scheme == "content") { "Choose a video using the file picker." }
        var name = "Video.mp4"; var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; if (!cursor.isNull(1)) size = cursor.getLong(1) }
        }
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            fun value(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
            val duration = value(MediaMetadataRetriever.METADATA_KEY_DURATION)
            require(duration in 60_000..ClipPlanner.MAX_DURATION_MS) { "Choose a video between 1 and 200 minutes long." }
            val rotation = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            val w = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val h = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            require(w > 0 && h > 0) { "This file does not contain a readable video track." }
            val extractor = MediaExtractor()
            var hdr = false
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                        hdr = transfer == 6 || transfer == 7
                    }
                }
            } finally { extractor.release() }
            VideoInfo(uri.toString(), name.take(200), duration,
                if (rotation % 180 != 0) h else w, if (rotation % 180 != 0) w else h,
                size, value(MediaMetadataRetriever.METADATA_KEY_BITRATE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), rotation, hdr)
        }
    }
}
