package com.yhct.pulsemaster.core

import kotlin.test.*

class ExamTest {
    @Test fun `plan has 18 unique points ordered hand region depth`() {
        val pts = ExamPlan.points
        assertEquals(18, pts.size); assertEquals(18, pts.toSet().size)
        assertEquals(ExamPoint(Hand.LEFT, Region.THON, Depth.PHU), pts[0])
        assertEquals(ExamPoint(Hand.LEFT, Region.THON, Depth.TRUNG), pts[1])
        assertEquals(ExamPoint(Hand.LEFT, Region.QUAN, Depth.PHU), pts[3])
        assertEquals(ExamPoint(Hand.RIGHT, Region.XICH, Depth.TRAM), pts[17])
        assertTrue(pts.none { it.depth == Depth.NONE })
    }

    /** Mô phỏng nhịp 800 ms, mẫu cảm ứng mỗi 20 ms. [depthAt] trả tầng tại thời điểm t. */
    private fun simulate(h: HoldTracker, until: Long, depthAt: (Long) -> Depth): Long? {
        var t = 0L
        while (t <= until) {
            h.onSample(depthAt(t), t)
            if (t > 0 && t % 800 == 0L && h.onBeat(t)) return t
            t += 20
        }
        return null
    }

    @Test fun `five consecutive beats in zone completes at the fifth beat`() {
        val h = HoldTracker(Depth.TRUNG)
        assertEquals(4000L, simulate(h, 10_000) { Depth.TRUNG })
        assertTrue(h.completed)
    }

    @Test fun `leaving the zone for a whole beat resets the streak`() {
        val h = HoldTracker(Depth.TRUNG)
        // nhịp thứ 3 (1600..2400) lệch tầng 300 ms
        val done = simulate(h, 12_000) { t -> if (t in 1700..2000) Depth.TRAM else Depth.TRUNG }
        // 2 nhịp đạt, nhịp 3 hỏng -> reset, cần 5 nhịp nữa kể từ nhịp 4 => hoàn tất ở nhịp thứ 8
        assertEquals(6400L, done)
    }

    @Test fun `brief wobble under grace is tolerated`() {
        val h = HoldTracker(Depth.PHU, graceMs = 100)
        assertEquals(4000L, simulate(h, 10_000) { t -> if (t in 1700..1740) Depth.TRUNG else Depth.PHU })
    }

    @Test fun `lifting the finger resets and wrong depth never completes`() {
        val h = HoldTracker(Depth.TRAM)
        assertNull(simulate(h, 20_000) { Depth.TRUNG })
        assertEquals(0, h.consecutive)
        val g = HoldTracker(Depth.TRUNG)
        for (t in 0..2400 step 20) { g.onSample(Depth.TRUNG, t.toLong()); if (t > 0 && t % 800 == 0) g.onBeat(t.toLong()) }
        assertEquals(3, g.consecutive); g.onLift(); assertEquals(0, g.consecutive)
        assertFalse(g.onBeat(3200), "beat không có mẫu cảm ứng không được tính")
    }

    @Test fun `none target is rejected and completion latches`() {
        assertFailsWith<IllegalArgumentException> { HoldTracker(Depth.NONE) }
        val h = HoldTracker(Depth.PHU, requiredBeats = 1)
        h.onSample(Depth.PHU, 0); assertTrue(h.onBeat(10)); assertFalse(h.onBeat(20)); assertTrue(h.completed)
    }

    @Test fun `session walks through all 18 points and reports`() {
        val s = ExamSession()
        repeat(17) { assertFalse(s.isFinished); s.record(0.5) }
        assertEquals(17, s.index); s.skip()
        assertTrue(s.isFinished); assertNull(s.current)
        assertFailsWith<IllegalStateException> { s.record(0.5) }
        assertEquals(17, s.captured.count { it.captured })
        assertEquals(18, s.summary().lines().filter { it.isNotBlank() }.size)
    }
}
