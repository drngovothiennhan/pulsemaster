package com.yhct.pulsemaster.core

import kotlin.test.*

class RhythmTest {
    private fun run(id: String, u: DoubleArray, n: Int, force: Double = 0.3, bpm: Double? = null): List<Beat> {
        val p = TestData.p(id)
        val s = BeatScheduler(p, bpm ?: p.defaultBpm, ScriptedUniform(u))
        return List(n) { s.next(force) }
    }

    @Test fun `regular never pauses and keeps exact period`() {
        val b = run("phu", doubleArrayOf(0.0), 50, bpm = 60.0)
        assertTrue(b.none { it.silent }); assertTrue(b.all { it.intervalMs == 1000.0 && it.amplitudeScale == 1.0 })
    }

    @Test fun `xuc drops a beat at random but never first and never two in a row`() {
        val b = run("xuc", doubleArrayOf(0.5, 0.1, 0.05, 0.9, 0.1, 0.3, 0.17, 0.19), 8)
        assertEquals(listOf(1, 4, 6), b.filter { it.silent }.map { it.index })
        // u thấp liên tục: vẫn không bao giờ có 2 nhịp bỏ liên tiếp và nhịp 0 luôn phát
        val all = run("ket", doubleArrayOf(0.0), 40)
        assertFalse(all[0].silent)
        assertTrue(all.zipWithNext().none { (x, y) -> x.silent && y.silent })
    }

    @Test fun `xuc long run matches pause probability statistically`() {
        val p = TestData.p("xuc")
        val s = BeatScheduler(p, uniform = SeededUniform(42))
        val n = 20_000
        val silent = (0 until n).count { s.next(0.5).silent }
        val rate = silent / n.toDouble()
        // do luật "không bỏ 2 nhịp liên tiếp" tỉ lệ thực ≈ p/(1+p) ≈ 0.153
        assertTrue(rate in 0.13..0.17, "rate=$rate")
    }

    @Test fun `dai rests one beat after every four`() {
        val b = run("dai", doubleArrayOf(0.5), 15)
        assertEquals(listOf(4, 9, 14), b.filter { it.silent }.map { it.index })
    }

    @Test fun `tan and sap jitter stays within plus minus 12 percent`() {
        for (id in listOf("tan", "sap")) {
            val p = TestData.p(id)
            val base = 60_000.0 / p.defaultBpm
            val lo = run(id, doubleArrayOf(0.0), 1)[0].intervalMs
            val hi = run(id, doubleArrayOf(0.999999999), 1)[0].intervalMs
            assertEquals(base * 0.88, lo, 1e-6); assertEquals(base * 1.12, hi, 1e-3)
            val seeded = BeatScheduler(p, uniform = SeededUniform(7))
            repeat(2000) { val i = seeded.next(0.5).intervalMs; assertTrue(i >= base * 0.88 - 1e-9 && i <= base * 1.12 + 1e-9) }
            assertTrue(run(id, doubleArrayOf(0.1, 0.9, 0.3), 10).none { it.silent })
        }
    }

    @Test fun `deep press fades amplitude for tan and sap only`() {
        for (id in listOf("tan", "sap")) {
            val light = run(id, doubleArrayOf(0.5), 1, force = 0.2)[0].amplitudeScale
            val deep = run(id, doubleArrayOf(0.5), 1, force = 1.0)[0].amplitudeScale
            assertEquals(1.0, light); assertTrue(deep < 1.0 && deep > 0.0, "$id deep=$deep")
        }
        assertEquals(1.0, run("hoat", doubleArrayOf(0.5), 1, force = 1.0)[0].amplitudeScale)
        assertEquals(1.0 - BeatScheduler.FADE_TAN, run("tan", doubleArrayOf(0.5), 1, force = 1.0)[0].amplitudeScale, 1e-12)
    }

    @Test fun `bpm is clamped to safe range`() {
        assertEquals(180.0, BeatScheduler(TestData.p("tat"), 999.0, ScriptedUniform(doubleArrayOf(0.5))).bpm)
        assertEquals(35.0, BeatScheduler(TestData.p("tri"), 1.0, ScriptedUniform(doubleArrayOf(0.5))).bpm)
    }

    @Test fun `random draws consumed exactly once per beat for random pulses and never for deterministic ones`() {
        var draws = 0
        val counting = UniformSource { draws++; 0.5 }
        for ((id, expect) in listOf("phu" to 0, "dai" to 0, "xuc" to 10, "tan" to 10, "sap" to 10)) {
            draws = 0
            val s = BeatScheduler(TestData.p(id), uniform = counting)
            repeat(10) { s.next(0.5) }
            assertEquals(expect, draws, id)
        }
    }
}
