package app.clipper.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.clipper.model.Cue
import app.clipper.model.VideoInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteOrder

/** Streams decoded audio through Vosk, keeping memory independent of the movie's length. */
class Transcriber(private val context: Context) {
    suspend fun transcribe(source: VideoInfo, onProgress: (Int) -> Unit): List<Cue> {
        val installer = ModelInstaller(context)
        check(installer.installed()) { "Install a speech language in Settings before generating subtitles." }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val cues = mutableListOf<Cue>()
        try {
            extractor.setDataSource(context, Uri.parse(source.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("This video has no audio track to transcribe.")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val audioOriginMs = (extractor.sampleTime / 1000).coerceAtLeast(0)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0); codec.start()
            Model(installer.modelDirectory().absolutePath).use { model ->
                Recognizer(model, 16_000f).use { recognizer ->
                    recognizer.setWords(true)
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false; var outputDone = false
                    var resampler = PcmResampler(sampleRate)
                    var lastProgress = -1
                    var lastOutputNs = System.nanoTime()
                    while (!outputDone) {
                        currentCoroutineContext().ensureActive()
                        check(System.nanoTime() - lastOutputNs < 60_000_000_000L) { "Audio decoder stopped responding. Try another video format." }
                        if (!inputDone) {
                            val inputIndex = codec.dequeueInputBuffer(10_000)
                            if (inputIndex >= 0) {
                                val buffer = codec.getInputBuffer(inputIndex)!!
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                                else {
                                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected audio cannot be transcribed." }
                                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance()
                                }
                            }
                        }
                        when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val actual = codec.outputFormat
                                val newRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (newRate != sampleRate) { sampleRate = newRate; resampler = PcmResampler(sampleRate) }
                                channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoded audio format." }
                            }
                            else -> if (outputIndex >= 0) {
                                lastOutputNs = System.nanoTime()
                                val buffer = codec.getOutputBuffer(outputIndex)!!.order(ByteOrder.nativeOrder())
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val pcm = java.io.ByteArrayOutputStream()
                                while (buffer.remaining() >= channels * bytesPerSample) {
                                    var mono = 0f
                                    repeat(channels) { mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f }
                                    resampler.push(mono / channels) { sample ->
                                        val value = (sample.coerceIn(-1f, 1f) * 32767).toInt()
                                        pcm.write(value and 255); pcm.write((value shr 8) and 255)
                                    }
                                }
                                val bytes = pcm.toByteArray()
                                if (bytes.isNotEmpty() && recognizer.acceptWaveForm(bytes, bytes.size)) appendResult(recognizer.result, cues, source.durationMs, audioOriginMs)
                                val progress = (info.presentationTimeUs / 10 / source.durationMs).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) { lastProgress = progress; onProgress(progress) }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                    appendResult(recognizer.finalResult, cues, source.durationMs, audioOriginMs)
                }
            }
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
        return cues
    }

    private fun appendResult(json: String, target: MutableList<Cue>, durationMs: Long, originMs: Long) {
        val words = JSONObject(json).optJSONArray("result") ?: return
        val group = mutableListOf<String>(); var start = 0L; var end = 0L
        fun flush() {
            if (group.isEmpty()) return
            val safeStart = maxOf(start, target.lastOrNull()?.endMs ?: 0)
            val safeEnd = minOf(end, durationMs)
            if (safeEnd > safeStart) target += Cue(safeStart, safeEnd, group.joinToString(" "))
            group.clear()
        }
        for (i in 0 until words.length()) {
            val word = words.getJSONObject(i)
            val wordStart = originMs + (word.getDouble("start") * 1000).toLong()
            val wordEnd = originMs + (word.getDouble("end") * 1000).toLong()
            if (group.isNotEmpty() && (group.size >= 8 || wordEnd - start > 4000 || wordStart - end > 700)) flush()
            if (group.isEmpty()) start = wordStart
            group += word.getString("word").take(80); end = wordEnd
        }
        flush()
    }
}

/** Stateful linear resampling avoids timing drift at decoder buffer boundaries. */
class PcmResampler(private val sourceRate: Int) {
    private var index = 0L
    private var nextOutput = 0.0
    private var previous = 0f
    init { require(sourceRate > 0) }
    fun push(sample: Float, emit: (Float) -> Unit) {
        while (nextOutput <= index) {
            val fraction = (nextOutput - (index - 1)).coerceIn(0.0, 1.0).toFloat()
            emit(if (index == 0L) sample else previous + (sample - previous) * fraction)
            nextOutput += sourceRate / 16000.0
        }
        previous = sample; index++
    }
}
