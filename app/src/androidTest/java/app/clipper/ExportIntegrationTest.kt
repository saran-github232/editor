package app.clipper

import android.content.Context
import android.media.*
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clipper.media.ExactExporter
import app.clipper.media.OriginalCopyExporter
import app.clipper.media.VideoInspector
import app.clipper.model.*
import app.clipper.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mainScreenAndNavigationLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.chooseVideo))
                activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_settings
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.settingsPage).visibility)
            }
        }
    }

    @Test fun originalCopyPreservesEveryCompressedVideoSample() = runBlocking {
        val sourceFile = fixture()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", sourceFile)
        val source = VideoInspector.inspect(context, uri)
        val project = Project(source = source, options = ExportOptions(mode = CutMode.ORIGINAL_COPY, partLabels = false), clips = ClipPlanner.plan(source.durationMs, 60, "fixture.mp4"))
        val exporter = OriginalCopyExporter(context)
        val aligned = exporter.alignedClips(project)
        assertEquals(2, aligned.size)
        assertEquals(aligned.first().endMs, aligned.last().startMs)
        val hashes = mutableListOf<String>()
        aligned.forEach { clip ->
            val output = File(sourceFile.parentFile, "copy-${clip.index}.mp4").apply { delete() }
            exporter.export(project, clip, output)
            hashes += samples(output)
        }
        assertEquals(samples(sourceFile), hashes)
    }

    @Test fun exactExportKeepsRequestedDimensionsAndDurationWithCaption() = runBlocking {
        val sourceFile = fixture()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", sourceFile)
        val source = VideoInspector.inspect(context, uri)
        val clip = Clip(1, 1000, 3500, "exact.mp4")
        val project = Project(source = source, options = ExportOptions(caption = "Integration test", partLabels = true), clips = listOf(clip))
        val output = File(sourceFile.parentFile, "exact.mp4").apply { delete() }
        ExactExporter(context).export(project, clip, listOf(Cue(0, 2000, "Subtitle timing")), output)
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.absolutePath)
            assertEquals("320", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("180", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("Unexpected duration: $duration", duration in 2400..2600)
            val frame = retriever.getFrameAtTime(1_000_000)
            assertNotNull(frame); frame?.recycle()
        }
    }

    @Test fun speechNativeLibraryLoads() {
        // Exercises JNA/native loading without network or a language model download.
        org.vosk.LibVosk.vosk_set_log_level(-1)
    }

    @Test fun offlineSpeechRecognizesKnownRecording() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in to the 40 MB model download with speechNetworkTest=true",
            InstrumentationRegistry.getArguments().getString("speechNetworkTest") == "true")
        val installer = app.clipper.speech.ModelInstaller(context)
        installer.select(app.clipper.speech.SpeechLanguage.ENGLISH)
        if (!installer.installed()) installer.install { }
        val directory = File(context.filesDir, "exports/test-fixture").apply { mkdirs() }
        val audio = File(directory, "speech-test.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("speech-test.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", audio)
        val duration = MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(audio.path)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        }
        val source = VideoInfo(uri.toString(), audio.name, duration, 320, 180, audio.length(), 0)
        val cues = app.clipper.speech.Transcriber(context).transcribe(source) { }
        assertTrue("No words recognized from the Vosk test recording", cues.isNotEmpty())
        assertTrue(cues.joinToString(" ") { it.text }.contains("zero"))
        assertTrue(cues.all { it.startMs >= 0 && it.endMs <= duration && it.endMs > it.startMs })
        assertEquals(cues, Subtitles.parseSrt(Subtitles.srt(cues), duration))
    }

    private fun fixture(): File {
        val directory = File(context.filesDir, "exports/test-fixture").apply { mkdirs() }
        val file = File(directory, "fixture.mp4")
        if (file.length() > 0) return file
        val codec = MediaCodec.createEncoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 320, 180).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000); setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 7)
        }
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            var frame = 0; var inputDone = false; var outputDone = false; var track = -1
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == 650) { codec.queueInputBuffer(index, 0, 0, 65_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            repeat(320 * 180) { buffer.put((40 + frame % 160).toByte()) }
                            repeat(320 * 180 / 2) { buffer.put(128.toByte()) }
                            codec.queueInputBuffer(index, 0, 320 * 180 * 3 / 2, frame * 100_000L, 0); frame++
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally { codec.stop(); codec.release(); if (muxStarted) muxer.stop(); muxer.release() }
        return file
    }
    private fun samples(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path); extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val result = mutableListOf<String>()
            while (true) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                val bytes = ByteArray(size); buffer.get(bytes)
                result += MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                extractor.advance()
            }
            return result
        } finally { extractor.release() }
    }
}
