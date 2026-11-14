package com.yhct.pulsemaster.core

import kotlin.test.*

class ForceModelTest {
    @Test fun `zone classification matches spec thresholds`() {
        assertEquals(Depth.NONE, Zones.classify(0.0)); assertEquals(Depth.NONE, Zones.classify(0.049))
        assertEquals(Depth.PHU, Zones.classify(0.05)); assertEquals(Depth.PHU, Zones.classify(0.349))
        assertEquals(Depth.TRUNG, Zones.classify(0.35)); assertEquals(Depth.TRUNG, Zones.classify(0.70))
        assertEquals(Depth.TRAM, Zones.classify(0.701)); assertEquals(Depth.TRAM, Zones.classify(1.0))
    }

    private val cal = ChannelCalibration(0.04, 0.09, 0.15)

    @Test fun `calibration hits anchors and is monotone and clamped`() {
        assertEquals(0.20, cal.normalize(0.04), 1e-9)
        assertEquals(0.525, cal.normalize(0.09), 1e-9)
        assertEquals(0.85, cal.normalize(0.15), 1e-9)
        assertEquals(0.0, cal.normalize(-5.0)); assertEquals(1.0, cal.normalize(5.0))
        var prev = -1.0
        for (i in 0..300) { val f = cal.normalize(i / 1000.0); assertTrue(f >= prev); prev = f }
    }

    @Test fun `anchors land in their own depth zone`() {
        assertEquals(Depth.PHU, Zones.classify(cal.normalize(0.04)))
        assertEquals(Depth.TRUNG, Zones.classify(cal.normalize(0.09)))
        assertEquals(Depth.TRAM, Zones.classify(cal.normalize(0.15)))
    }

    @Test fun `calibration rejects indistinct or invalid levels`() {
        assertFailsWith<CalibrationException> { ChannelCalibration(0.5, 0.5, 0.5) }
        assertFailsWith<CalibrationException> { ChannelCalibration(0.09, 0.04, 0.15) }
        assertFailsWith<CalibrationException> { ChannelCalibration(0.1, 0.101, 0.102) }
        assertFailsWith<CalibrationException> { ChannelCalibration(Double.NaN, 1.0, 2.0) }
        assertFailsWith<CalibrationException> { FingerCalibration(null, null) }
    }

    @Test fun `fusion weights pressure 0_6 and size 0_4 and falls back to a single channel`() {
        val p = ChannelCalibration(0.2, 0.5, 0.9)
        val both = FingerCalibration(p, cal)
        val raw = RawSample(0.5, 0.15)
        assertEquals(0.6 * 0.525 + 0.4 * 0.85, both.fuse(raw), 1e-9)
        assertEquals(0.85, FingerCalibration(null, cal).fuse(raw), 1e-9)
        assertEquals(0.525, FingerCalibration(p, null).fuse(raw), 1e-9)
    }

    @Test fun `calibration json round trip`() {
        val c = FingerCalibration(ChannelCalibration(0.2, 0.5, 0.9), null)
        val r = FingerCalibration.fromJson(c.toJson())
        assertEquals(0.5, r.pressure!!.medium); assertNull(r.size)
        assertFailsWith<CalibrationException> { FingerCalibration.fromJson("{\"v\":2}") }
        assertFailsWith<PulseDataException> { FingerCalibration.fromJson("garbage") }
    }

    private fun record(rec: CalibrationRecorder, level: (CalibrationPhase) -> RawSample, step: Long = 50L, count: Int = 100) {
        rec.start(0)
        for (i in 0 until count) {
            val t = i * step
            val ph = rec.phaseAt(t) ?: break
            assertNotNull(rec.addSample(t, level(ph)))
        }
    }

    @Test fun `recorder derives both channels on a device with real pressure`() {
        val rec = CalibrationRecorder()
        record(rec, { when (it) {
            CalibrationPhase.LIGHT -> RawSample(0.15, 0.04)
            CalibrationPhase.MEDIUM -> RawSample(0.40, 0.09)
            CalibrationPhase.HARD -> RawSample(0.80, 0.15)
        } })
        val c = rec.finish()
        assertNotNull(c.pressure); assertNotNull(c.size)
        assertEquals(Depth.PHU, Zones.classify(c.fuse(RawSample(0.15, 0.04))))
        assertEquals(Depth.TRUNG, Zones.classify(c.fuse(RawSample(0.40, 0.09))))
        assertEquals(Depth.TRAM, Zones.classify(c.fuse(RawSample(0.80, 0.15))))
    }

    @Test fun `recorder drops the fixed pressure channel (always 0_5) and keeps size`() {
        val rec = CalibrationRecorder()
        record(rec, { when (it) {
            CalibrationPhase.LIGHT -> RawSample(0.5, 0.04)
            CalibrationPhase.MEDIUM -> RawSample(0.5, 0.09)
            CalibrationPhase.HARD -> RawSample(0.5, 0.15)
        } })
        val c = rec.finish()
        assertNull(c.pressure); assertNotNull(c.size)
    }

    @Test fun `recorder ignores settling samples via median`() {
        val rec = CalibrationRecorder()
        rec.start(0)
        var n = 0
        for (i in 0 until 100) {
            val t = i * 50L
            val ph = rec.phaseAt(t) ?: break
            val base = when (ph) { CalibrationPhase.LIGHT -> 0.04; CalibrationPhase.MEDIUM -> 0.09; else -> 0.15 }
            val spike = if (n++ % 11 == 0) 0.5 else 0.0 // gai nhiễu
            rec.addSample(t, RawSample(0.5, base + spike))
        }
        val c = rec.finish()
        assertEquals(0.09, c.size!!.medium, 0.005)
    }

    @Test fun `recorder fails clearly when finger lifted or nothing changes`() {
        val rec = CalibrationRecorder()
        rec.start(0)
        for (i in 0 until 3) rec.addSample(i * 50L, RawSample(0.5, 0.05)) // quá ít mẫu
        assertFailsWith<CalibrationException> { rec.finish() }

        val flat = CalibrationRecorder()
        record(flat, { RawSample(0.5, 0.08) })
        assertFailsWith<CalibrationException> { flat.finish() }
    }

    @Test fun `recorder timing`() {
        val r = CalibrationRecorder()
        assertNull(r.phaseAt(0)); r.start(1000)
        assertEquals(CalibrationPhase.LIGHT, r.phaseAt(1000))
        assertEquals(CalibrationPhase.MEDIUM, r.phaseAt(1000 + 1700))
        assertEquals(CalibrationPhase.HARD, r.phaseAt(1000 + 3400))
        assertEquals(CalibrationPhase.HARD, r.phaseAt(1000 + 4999))
        assertNull(r.phaseAt(1000 + 5000)); assertTrue(r.isFinished(6000))
        assertNull(r.addSample(7000, RawSample(0.5, 0.1)))
        assertNull(r.addSample(1100, RawSample(Double.NaN, 0.1)))
    }

    @Test fun `depth tracker has hysteresis and no flicker at boundary`() {
        val t = DepthTracker()
        assertEquals(Depth.NONE, t.update(0.03))
        assertEquals(Depth.PHU, t.update(0.2))
        assertEquals(Depth.PHU, t.update(0.36))   // trên 0.35 nhưng chưa vượt 0.37
        assertEquals(Depth.TRUNG, t.update(0.371))
        assertEquals(Depth.TRUNG, t.update(0.34)) // dưới 0.35 nhưng chưa xuống ≤ 0.33
        assertEquals(Depth.PHU, t.update(0.329))
        assertEquals(Depth.TRAM, t.update(0.95))  // nhảy nhiều tầng
        assertEquals(Depth.NONE, t.update(0.0))
        t.update(0.5); t.reset(); assertEquals(Depth.NONE, t.depth)
    }

    @Test fun `filter smooths and resets`() {
        val f = ForceFilter(0.5)
        assertEquals(1.0, f.update(1.0)); assertEquals(0.5, f.update(0.0)); f.reset(); assertEquals(0.3, f.update(0.3))
    }
}
