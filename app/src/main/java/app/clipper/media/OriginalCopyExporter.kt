package app.clipper.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.clipper.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** Copies compressed samples. No decoder, encoder, resize, or lossy fallback. */
class OriginalCopyExporter(private val context: Context) {
    suspend fun alignedClips(project: Project): List<Clip> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(project.source.uri), null)
            val video = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(video)
            val times = mutableListOf<Long>()
            // Seek per boundary instead of scanning several hours of video.
            for (clip in project.clips.drop(1)) {
                currentCoroutineContext().ensureActive()
                extractor.seekTo(clip.startMs * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
                if (extractor.sampleTime >= clip.startMs * 1000) times += extractor.sampleTime / 1000
            }
            return ClipPlanner.alignToSyncSamples(project.clips, times, project.source.durationMs)
        } finally { extractor.release() }
    }

    suspend fun export(project: Project, clip: Clip, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, Uri.parse(project.source.uri), null)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(project.source.rotation)
            val tracks = (0 until extractor.trackCount).filter {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }
            require(tracks.isNotEmpty()) { "No supported audio or video tracks." }
            val mapping = tracks.associateWith { index ->
                val format = extractor.getTrackFormat(index)
                require(!format.containsKey("crypto-key")) { "Protected videos cannot be exported." }
                muxer.addTrack(format)
            }
            val videoTrack = tracks.first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            extractor.selectTrack(videoTrack)
            extractor.seekTo(clip.startMs * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val originUs = extractor.sampleTime
            require(originUs >= 0 && originUs < clip.endMs * 1000) { "No keyframe in this clip. Try exact mode." }
            extractor.unselectTrack(videoTrack)
            muxer.start()
            var buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (track in tracks) {
                extractor.selectTrack(track)
                extractor.seekTo(originUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleTime >= 0 && extractor.sampleTime < clip.endMs * 1000) {
                    currentCoroutineContext().ensureActive()
                    if (extractor.sampleTime < originUs) { extractor.advance(); continue }
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected video cannot be copied." }
                    val sampleSize = extractor.sampleSize
                    require(sampleSize in 0..64L * 1024 * 1024) { "Video sample is too large for this device." }
                    if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, extractor.sampleTime - originUs, flags)
                    muxer.writeSampleData(mapping.getValue(track), buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(track)
            }
            muxer.stop()
        } finally { extractor.release(); muxer?.release() }
    }
}
