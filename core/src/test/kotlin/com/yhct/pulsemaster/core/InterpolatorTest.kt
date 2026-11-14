package com.yhct.pulsemaster.core

import kotlin.test.*

class InterpolatorTest {
    private val a = DepthAmplitude(0.9, 0.55, 0.25) // mạch Phù
    private val modes = InterpolationMode.entries

    @Test fun `passes through anchors exactly`() {
        for (m in modes) {
            assertEquals(0.9, DepthInterpolator.amplitudeAt(a, Zones.CENTER_PHU, m), 1e-12)
            assertEquals(0.55, DepthInterpolator.amplitudeAt(a, Zones.CENTER_TRUNG, m), 1e-12)
            assertEquals(0.25, DepthInterpolator.amplitudeAt(a, Zones.CENTER_TRAM, m), 1e-12)
        }
    }

    @Test fun `linear midpoint is exact`() {
        val mid = (Zones.CENTER_PHU + Zones.CENTER_TRUNG) / 2
        assertEquals((0.9 + 0.55) / 2, DepthInterpolator.amplitudeAt(a, mid, InterpolationMode.LINEAR), 1e-12)
    }

    @Test fun `monotone cubic never overshoots anchors for every pulse`() {
        for (p in TestData.catalog.pulses) {
            val d = p.depthAmplitude
            val lo = minOf(d.phu, d.trung, d.tram); val hi = maxOf(d.phu, d.trung, d.tram)
            for (i in 20..100) {   // vùng đã qua cổng chạm
                val v = DepthInterpolator.amplitudeAt(d, i / 100.0)
                assertTrue(v >= lo - 1e-9 && v <= hi + 1e-9, "${p.id} f=${i / 100.0} v=$v ∉ [$lo,$hi]")
            }
        }
    }

    @Test fun `curve is continuous with no jumps between neighbouring forces`() {
        for (p in TestData.catalog.pulses) for (m in modes) {
            var prev = DepthInterpolator.amplitudeAt(p.depthAmplitude, 0.0, m)
            for (i in 1..1000) {
                val v = DepthInterpolator.amplitudeAt(p.depthAmplitude, i / 1000.0, m)
                assertTrue(Math.abs(v - prev) < 0.03, "${p.id} $m nhảy ${Math.abs(v - prev)} tại ${i / 1000.0}")
                prev = v
            }
        }
    }

    @Test fun `monotone data stays monotone`() {
        val up = DepthAmplitude(0.1, 0.4, 0.9) // mạch Trầm
        var prev = 0.0
        for (i in 20..100) {
            val v = DepthInterpolator.amplitudeAt(up, i / 100.0); assertTrue(v >= prev - 1e-12); prev = v
        }
    }

    @Test fun `no contact gives silence and light touch fades in`() {
        assertEquals(0.0, DepthInterpolator.amplitudeAt(a, 0.0)); assertEquals(0.0, DepthInterpolator.amplitudeAt(a, 0.03))
        val v = DepthInterpolator.amplitudeAt(a, 0.065)
        assertTrue(v > 0.0 && v < 0.9)
        assertEquals(0.9, DepthInterpolator.amplitudeAt(a, 0.12), 1e-12)
    }

    @Test fun `out of range force is clamped`() {
        assertEquals(DepthInterpolator.amplitudeAt(a, 1.0), DepthInterpolator.amplitudeAt(a, 7.0))
        assertEquals(0.0, DepthInterpolator.amplitudeAt(a, -3.0))
    }

    @Test fun `khau keeps its hollow middle layer`() {
        val k = TestData.p("khau").depthAmplitude
        val mid = DepthInterpolator.amplitudeAt(k, Zones.CENTER_TRUNG)
        assertTrue(mid < 0.2 && DepthInterpolator.amplitudeAt(k, 0.2) > 0.8 && DepthInterpolator.amplitudeAt(k, 0.85) > 0.7)
    }
}
