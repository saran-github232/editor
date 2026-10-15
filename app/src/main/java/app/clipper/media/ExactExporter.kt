package app.clipper.media

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import app.clipper.model.*
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class ExactExporter(private val context: Context) {
    suspend fun export(project: Project, clip: Clip, cues: List<Cue>, output: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val options = project.options
            val (width, height) = ClipPlanner.outputSize(project.source.width, project.source.height, options.shortEdge)
            val overlays = mutableListOf<TextureOverlay>()
            val label = listOf(options.caption, if (options.partLabels) options.partTemplate.replace("{part}", clip.index.toString()) else "")
                .filter { it.isNotBlank() }.joinToString("\n")
            if (label.isNotBlank()) overlays += captionOverlay(label, options.captionPosition)
            if (options.burnSubtitles && cues.isNotEmpty()) overlays += object : TextOverlay() {
                override fun getText(presentationTimeUs: Long): SpannableString {
                    val ms = presentationTimeUs / 1000
                    val index = cues.binarySearch { cue -> when { ms < cue.startMs -> 1; ms >= cue.endMs -> -1; else -> 0 } }
                    return styled(if (index >= 0) cues[index].text else "")
                }
                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = placement(Position.BOTTOM, if (options.captionPosition == Position.BOTTOM && label.isNotBlank()) 0.30f else 0.12f)
            }
            val effects = mutableListOf<androidx.media3.common.Effect>()
            effects += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            if (overlays.isNotEmpty()) effects += OverlayEffect(ImmutableList.copyOf(overlays))
            val item = MediaItem.Builder().setUri(Uri.parse(project.source.uri))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
            val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
            val sourcePixels = project.source.width.toLong() * project.source.height
            val bitrate = (maxOf(project.source.bitrate, 8_000_000).toLong() * width * height / sourcePixels).coerceIn(2_000_000, 80_000_000).toInt()
            val encoder = DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoder)
                .setVideoMimeType(if (project.source.hdr) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                            "This device could not encode the selected format/resolution. Try a lower resolution or Original copy. ${exportException.errorCodeName}", exportException))
                    }
                }).build()
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
            try { transformer.start(edited, output.absolutePath) }
            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }

    private fun captionOverlay(text: String, position: Position): TextOverlay = TextOverlay.createStaticTextOverlay(styled(text), placement(position, 0.12f))
    private fun placement(position: Position, margin: Float): StaticOverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, if (position == Position.TOP) 1f - margin else -1f + margin)
        .setOverlayFrameAnchor(0f, if (position == Position.TOP) 1f else -1f).build()
    private fun styled(text: String): SpannableString {
        val wrapped = text.split('\n').joinToString("\n") { line ->
            line.split(' ').fold(mutableListOf("")) { lines, word ->
                if ((lines.last() + " " + word).length > 38) lines.add(word)
                else lines[lines.lastIndex] = (lines.last() + " " + word).trim()
                lines
            }.joinToString("\n")
        }
        return SpannableString(wrapped).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(0xB3000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(30), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
