package com.yhct.pulsemaster.core

import kotlin.test.*

class WaveformTest {
    @Test fun `waveform peaks at upstroke and shows dicrotic bump above the notch`() {
        val p = TestData.p("hoat")
        val w = WaveformSynth.synth(p, 750.0)
        assertEquals(0.0, w.levelAt(0.0)); assertEquals(1.0, w.levelAt(p.upstrokeMs), 1e-12)
        val notch = w.levelAt(p.upstrokeMs + WaveformSynth.NOTCH_DELAY_MS)
        val dic = w.levelAt(p.upstrokeMs + WaveformSynth.DICROTIC_DELAY_MS)
        assertEquals(p.dicroticRatio, dic, 1e-12)
        assertTrue(notch < dic, "phải có khuyết trước sóng dội")
        assertEquals(0.0, w.levelAt(w.durationMs + 1))
    }

    @Test fun `every pulse fits inside its beat interval at every bpm`() {
        for (p in TestData.catalog.pulses) for (bpm in listOf(35.0, p.defaultBpm, 150.0, 180.0)) {
            val interval = 60_000.0 / bpm
            val w = WaveformSynth.synth(p, interval)
            assertTrue(w.durationMs <= interval * WaveformSynth.MAX_FILL + 1e-9, "${p.id} @$bpm")
            assertTrue(w.points.zipWithNext().all { (a, b) -> b.tMs > a.tMs }, "thời gian phải tăng dần ${p.id}")
            assertTrue(w.points.all { it.level in 0.0..1.0 })
        }
    }

    @Test fun `vibration steps are valid for android`() {
        val w = WaveformSynth.synth(TestData.p("sac"), 600.0)
        val s = w.toVibrationSteps(0.8, 10)
        assertEquals(s.timings.size, s.amplitudes.size)
        assertTrue(s.timings.all { it == 10L } && s.amplitudes.all { it in 0..255 })
        assertTrue(s.amplitudes.max() in (0.95 * 0.8 * 255).toInt()..(0.8 * 255).toInt(), "đỉnh trong 5% của peak·255 (lấy mẫu 10 ms)")
        assertTrue(s.amplitudes.last() <= 30, "kết thúc gần 0")
        assertContentEquals(IntArray(s.amplitudes.size), w.toVibrationSteps(0.0).amplitudes)
        assertFailsWith<IllegalArgumentException> { w.toVibrationSteps(1.0, 0) }
    }

    @Test fun `intensity curve is within 0 to 1`() {
        val c = WaveformSynth.synth(TestData.p("huyen"), 800.0).toIntensityCurve(1.7)
        assertTrue(c.all { it.second in 0.0..1.0 } && c.zipWithNext().all { (a, b) -> b.first > a.first })
    }
}
