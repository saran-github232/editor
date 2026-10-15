package app.clipper.model

import org.junit.Assert.*
import org.junit.Test

class ClipPlannerTest {
    @Test fun `200 minute movie produces 200 ordered one minute clips without gaps`() {
        val clips = ClipPlanner.plan(12_000_000, 60, "Movie.mp4")
        assertEquals(200, clips.size)
        assertEquals(0L, clips.first().startMs)
        assertEquals(12_000_000L, clips.last().endMs)
        assertTrue(clips.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertEquals(clips.map { it.fileName }, clips.map { it.fileName }.sorted())
    }
    @Test fun `remainder is preserved and exact multiple has no empty clip`() {
        assertEquals(1_234L, ClipPlanner.plan(121_234, 60, "a.mp4").last().let { it.endMs - it.startMs })
        assertEquals(2, ClipPlanner.plan(120_000, 60, "a.mp4").size)
    }
    @Test fun `all five intervals cover every millisecond`() {
        listOf(60, 90, 120, 150, 180).forEach { seconds ->
            val clips = ClipPlanner.plan(10_123_456, seconds, "Episode.mp4")
            assertEquals(10_123_456L, clips.sumOf { it.endMs - it.startMs })
            assertTrue(clips.all { it.endMs > it.startMs })
        }
    }
    @Test fun `short source gets one clip even with longer target`() {
        assertEquals(1, ClipPlanner.plan(60_000, 180, "a").size)
    }
    @Test(expected = IllegalArgumentException::class) fun `reject too short`() { ClipPlanner.plan(59_999, 60, "a") }
    @Test(expected = IllegalArgumentException::class) fun `reject too long`() { ClipPlanner.plan(12_000_001, 60, "a") }
    @Test fun `filename cannot escape directory`() {
        val name = ClipPlanner.plan(60_000, 60, "../../unsafe\\movie.mp4").single().fileName
        assertFalse(name.contains('/')); assertFalse(name.contains('\\')); assertFalse(name.contains(".."))
    }
    @Test fun `keyframe boundaries are deduplicated and preserve whole timeline`() {
        val clips = ClipPlanner.plan(180_000, 60, "source.mp4")
        val aligned = ClipPlanner.alignToSyncSamples(clips, listOf(125_000, 125_000), 180_000)
        assertEquals(2, aligned.size)
        assertEquals(125_000L, aligned.first().endMs)
        assertEquals(125_000L, aligned.last().startMs)
        assertEquals(180_000L, aligned.sumOf { it.endMs - it.startMs })
    }
    @Test fun `source size never upscales and portrait aspect is retained`() {
        assertEquals(1280 to 720, ClipPlanner.outputSize(1280, 720, 2160))
        assertEquals(720 to 1280, ClipPlanner.outputSize(1080, 1920, 720))
        assertEquals(3840 to 2160, ClipPlanner.outputSize(3840, 2160, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun `copy mode rejects labels`() {
        ExportOptions(mode = CutMode.ORIGINAL_COPY, partLabels = true).validate()
    }
    @Test fun `copy mode permits separate subtitles`() {
        ExportOptions(mode = CutMode.ORIGINAL_COPY, partLabels = false, autoSubtitles = true, burnSubtitles = false).validate()
    }
}
