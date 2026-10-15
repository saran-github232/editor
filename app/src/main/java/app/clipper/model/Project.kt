package app.clipper.model

import java.util.Locale
import java.util.UUID

enum class CutMode { EXACT, ORIGINAL_COPY }
enum class JobStatus { READY, RUNNING, PAUSED, FAILED, COMPLETE }
enum class Position { TOP, BOTTOM }

data class VideoInfo(
    val uri: String, val name: String, val durationMs: Long,
    val width: Int, val height: Int, val sizeBytes: Long, val bitrate: Int,
    val rotation: Int = 0, val hdr: Boolean = false
)

data class ExportOptions(
    val segmentSeconds: Int = 60,
    val mode: CutMode = CutMode.EXACT,
    val shortEdge: Int = 0,
    val caption: String = "",
    val captionPosition: Position = Position.TOP,
    val partLabels: Boolean = true,
    val partTemplate: String = "Part {part}",
    val autoSubtitles: Boolean = false,
    val burnSubtitles: Boolean = true,
    val uploadToDrive: Boolean = false
) {
    fun validate() {
        require(segmentSeconds in listOf(60, 90, 120, 150, 180)) { "Choose a clip length between 1 and 3 minutes." }
        require(shortEdge in listOf(0, 420, 720, 1080, 2160)) { "Choose a supported resolution." }
        require(caption.length <= 160 && partTemplate.length <= 80) { "Caption text is too long." }
        if (mode == CutMode.ORIGINAL_COPY) {
            require(shortEdge == 0 && caption.isBlank() && !partLabels && !(autoSubtitles && burnSubtitles)) {
                "Original copy needs source resolution, no text overlays, and sidecar-only subtitles."
            }
        }
    }
}

data class Clip(
    val index: Int, val startMs: Long, val endMs: Long,
    val fileName: String, val complete: Boolean = false,
    val driveId: String? = null, val uploadSession: String? = null,
    val subtitleDriveId: String? = null,
    val driveUploaded: Boolean = false,
    val savedUri: String? = null
)

data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val source: VideoInfo,
    val options: ExportOptions,
    val clips: List<Clip>,
    val status: JobStatus = JobStatus.READY,
    val message: String = "Ready to export",
    val progress: Int = 0,
    val driveFolderId: String? = null,
    val driveFolderCreated: Boolean = false,
    val subtitlesReady: Boolean = false
)

object ClipPlanner {
    const val MAX_DURATION_MS = 200L * 60 * 1000

    fun plan(durationMs: Long, seconds: Int, name: String): List<Clip> {
        require(durationMs in 60_000..MAX_DURATION_MS) { "Choose a video between 1 and 200 minutes long." }
        require(seconds in listOf(60, 90, 120, 150, 180))
        val length = seconds * 1000L
        val count = ((durationMs + length - 1) / length).toInt()
        val safeName = safeName(name.substringBeforeLast('.', name))
        return (0 until count).map { i ->
            Clip(i + 1, i * length, minOf((i + 1) * length, durationMs),
                String.format(Locale.ROOT, "%s_part_%03d.mp4", safeName, i + 1))
        }
    }

    fun safeName(name: String): String = name.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
        .trim().take(60).ifBlank { "Video" }

    /** Copy-mode boundaries use the first sync sample at/after each target, once for the entire job. */
    fun alignToSyncSamples(clips: List<Clip>, syncTimesMs: List<Long>, durationMs: Long): List<Clip> {
        val sorted = syncTimesMs.distinct().sorted()
        val boundaries = (listOf(0L) + clips.drop(1).mapNotNull { clip ->
            sorted.firstOrNull { it >= clip.startMs && it < durationMs }
        } + durationMs).distinct().sorted()
        val base = clips.first().fileName.substringBeforeLast("_part_")
        return boundaries.zipWithNext().mapIndexed { i, (start, end) ->
            Clip(i + 1, start, end, String.format(Locale.ROOT, "%s_part_%03d.mp4", base, i + 1))
        }
    }

    fun outputSize(width: Int, height: Int, shortEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = if (shortEdge == 0) 1.0 else minOf(1.0, shortEdge.toDouble() / minOf(width, height))
        return (maxOf(2, (width * scale).toInt() / 2 * 2) to maxOf(2, (height * scale).toInt() / 2 * 2))
    }
}

object Subtitles {
    fun forClip(cues: List<Cue>, startMs: Long, endMs: Long): List<Cue> = cues
        .filter { it.endMs > startMs && it.startMs < endMs }
        .map { Cue(maxOf(0, it.startMs - startMs), minOf(endMs, it.endMs) - startMs, it.text) }

    fun srt(cues: List<Cue>): String = cues.mapIndexed { i, cue ->
        "${i + 1}\n${stamp(cue.startMs)} --> ${stamp(cue.endMs)}\n${cue.text}\n"
    }.joinToString("\n")

    fun parseSrt(text: String, durationMs: Long): List<Cue> {
        require(text.length <= 2_000_000) { "Subtitle file is too large." }
        if (text.isBlank()) return emptyList()
        val result = text.trim().replace("\r", "").split(Regex("\n\\s*\n")).map { block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { " --> " in it }
            require(timeIndex >= 0 && timeIndex < lines.lastIndex) { "Use SRT format with a time range and text for every subtitle." }
            val stamps = lines[timeIndex].split(" --> ")
            require(stamps.size == 2)
            val start = parseStamp(stamps[0]); val end = parseStamp(stamps[1])
            val caption = lines.drop(timeIndex + 1).joinToString("\n")
            require(start >= 0 && end > start && end <= durationMs && caption.length <= 300) { "Check subtitle timing and keep each cue under 300 characters." }
            Cue(start, end, caption)
        }
        require(result.size <= 50_000)
        require(result.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) { "Subtitle cues must be in order without overlap." }
        return result
    }

    private fun parseStamp(value: String): Long {
        val match = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})").matchEntire(value.trim())
            ?: error("Use HH:MM:SS,mmm timestamps.")
        val (h, m, s, ms) = match.destructured
        require(m.toInt() < 60 && s.toInt() < 60)
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    private fun stamp(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

/** Deliberately local and predictable. No prompt or video is sent to an AI provider. */
object CaptionCommand {
    data class Result(val text: String, val position: Position)
    fun parse(command: String): Result? {
        val pattern = Regex("(?:put|add|write)\\s+[\"“](.+)[\"”]\\s+(?:at|on)\\s+(?:the\\s+)?(top|bottom)", RegexOption.IGNORE_CASE)
        val match = pattern.matchEntire(command.trim()) ?: return null
        return Result(match.groupValues[1].take(160), if (match.groupValues[2].equals("top", true)) Position.TOP else Position.BOTTOM)
    }
}
