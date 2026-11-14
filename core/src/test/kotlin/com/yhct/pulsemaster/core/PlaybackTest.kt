package com.yhct.pulsemaster.core

import kotlin.test.*

class PlaybackTest {
    private fun pb(id: String, u: DoubleArray = doubleArrayOf(0.5)) = PulsePlayback(TestData.p(id), uniform = ScriptedUniform(u))

    @Test fun `no finger gives a silent beat and keeps time`() {
        val plan = pb("phu").planBeat(emptyMap())
        assertTrue(plan.silent); assertEquals(0.0, plan.peak); assertNull(plan.dominantRegion)
        assertEquals(60_000.0 / 72, plan.intervalMs, 1e-9)
    }

    @Test fun `doan is strongest at quan and weaker at thon and xich`() {
        val f = 0.525
        val q = pb("doan").planBeat(mapOf(Region.QUAN to f)).peak
        val t = pb("doan").planBeat(mapOf(Region.THON to f)).peak
        val x = pb("doan").planBeat(mapOf(Region.XICH to f)).peak
        assertEquals(0.65, q, 1e-9); assertEquals(0.65 * 0.35, t, 1e-9); assertEquals(0.65 * 0.30, x, 1e-9)
    }

    @Test fun `three fingers on a tablet pick the strongest region`() {
        val plan = pb("dong").planBeat(mapOf(Region.THON to 0.525, Region.QUAN to 0.525, Region.XICH to 0.525))
        assertEquals(Region.QUAN, plan.dominantRegion); assertEquals(0.9, plan.peak, 1e-9)
    }

    @Test fun `silent beats of dai carry zero peak but a waveform and interval`() {
        val p = pb("dai")
        val plans = List(5) { p.planBeat(mapOf(Region.QUAN to 0.5)) }
        assertTrue(plans[4].silent && plans[4].peak == 0.0 && plans.take(4).none { it.silent })
    }

    @Test fun `phu fades with press depth while tram grows`() {
        val phu = TestData.p("phu").depthAmplitude; val tram = TestData.p("tram").depthAmplitude
        fun a(d: DepthAmplitude, f: Double) = DepthInterpolator.amplitudeAt(d, f)
        assertTrue(a(phu, 0.2) > a(phu, 0.525) && a(phu, 0.525) > a(phu, 0.85))
        assertTrue(a(tram, 0.2) < a(tram, 0.525) && a(tram, 0.525) < a(tram, 0.85))
    }

    @Test fun `deep fade lowers tan peak`() {
        val light = pb("tan").planBeat(mapOf(Region.QUAN to 0.2)).peak
        assertTrue(light > 0.0)
        val deepScale = pb("tan").planBeat(mapOf(Region.QUAN to 1.0))
        assertEquals(DepthInterpolator.amplitudeAt(TestData.p("tan").depthAmplitude, 1.0) * (1 - BeatScheduler.FADE_TAN), deepScale.peak, 1e-9)
    }
}
