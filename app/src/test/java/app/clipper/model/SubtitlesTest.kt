package app.clipper.model

import org.junit.Assert.*
import org.junit.Test

class SubtitlesTest {
    @Test fun `cue across a cut exists in both clips with rebased timestamps`() {
        val cues = listOf(Cue(59_000, 62_000, "Across the boundary"))
        assertEquals(Cue(59_000, 60_000, "Across the boundary"), Subtitles.forClip(cues, 0, 60_000).single())
        assertEquals(Cue(0, 2000, "Across the boundary"), Subtitles.forClip(cues, 60_000, 120_000).single())
    }
    @Test fun `cue exactly at boundary is not duplicated`() {
        val cues = listOf(Cue(60_000, 61_000, "Next part"))
        assertTrue(Subtitles.forClip(cues, 0, 60_000).isEmpty())
        assertEquals(1, Subtitles.forClip(cues, 60_000, 120_000).size)
    }
    @Test fun `srt edits round trip with multiline and unicode text`() {
        val cues = listOf(Cue(0, 1000, "Hello\nनमस्ते"), Cue(3_600_000, 3_601_250, "One hour later"))
        assertEquals(cues, Subtitles.parseSrt(Subtitles.srt(cues), 4_000_000))
    }
    @Test(expected = IllegalArgumentException::class) fun `reject inverted timing`() {
        Subtitles.parseSrt("1\n00:00:02,000 --> 00:00:01,000\nOops", 60_000)
    }
    @Test(expected = IllegalArgumentException::class) fun `reject overlaps`() {
        Subtitles.parseSrt(Subtitles.srt(listOf(Cue(0, 2000, "A"), Cue(1000, 3000, "B"))), 60_000)
    }
    @Test fun `offline command parser only accepts supported instructions`() {
        assertEquals(CaptionCommand.Result("My caption", Position.BOTTOM), CaptionCommand.parse("Put \"My caption\" at the bottom"))
        assertNull(CaptionCommand.parse("upload my movie somewhere"))
    }
}
