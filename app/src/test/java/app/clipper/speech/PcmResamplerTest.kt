package app.clipper.speech

import org.junit.Assert.*
import org.junit.Test

class PcmResamplerTest {
    @Test fun `48000 samples produce one second at 16k without buffer drift`() {
        val resampler = PcmResampler(48000)
        val samples = mutableListOf<Float>()
        repeat(48000) { resampler.push(0.25f) { samples += it } }
        assertEquals(16000, samples.size)
        assertTrue(samples.all { it == 0.25f })
    }
    @Test fun `44100 rate keeps subtitle clock accurate over ten seconds`() {
        val resampler = PcmResampler(44100)
        var count = 0
        repeat(441000) { resampler.push(0f) { count++ } }
        assertTrue(kotlin.math.abs(count - 160000) <= 1)
    }
}
