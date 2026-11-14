package com.yhct.pulsemaster.core

data class EnvelopePoint(val tMs: Double, val level: Double)

/** Đường cong 1 nhịp, level tương đối so với đỉnh (đỉnh chủ ba = 1.0). */
class BeatWaveform(val points: List<EnvelopePoint>) {
    val durationMs: Double get() = points.last().tMs

    fun levelAt(tMs: Double): Double {
        if (tMs <= points.first().tMs || tMs >= points.last().tMs) return 0.0
        var i = 1
        while (points[i].tMs < tMs) i++
        val a = points[i - 1]
        val b = points[i]
        val span = b.tMs - a.tMs
        return if (span <= 0.0) b.level else a.level + (tMs - a.tMs) / span * (b.level - a.level)
    }

    /** Chuỗi bước cho `VibrationEffect.createWaveform` (biên độ 0..255, mỗi bước [stepMs]). */
    fun toVibrationSteps(peak: Double, stepMs: Int = 10): VibrationSteps {
        require(stepMs > 0) { "stepMs phải > 0" }
        val n = Math.ceil(durationMs / stepMs).toInt().coerceAtLeast(1)
        val timings = LongArray(n) { stepMs.toLong() }
        val amps = IntArray(n) { i ->
            Math.round(levelAt((i + 0.5) * stepMs) * peak * 255.0).toInt().coerceIn(0, 255)
        }
        return VibrationSteps(timings, amps)
    }

    /** Điểm điều khiển cường độ (giây, 0..1) cho CHHapticParameterCurve. */
    fun toIntensityCurve(peak: Double): List<Pair<Double, Double>> =
        points.map { it.tMs / 1000.0 to (it.level * peak).coerceIn(0.0, 1.0) }
}

class VibrationSteps(val timings: LongArray, val amplitudes: IntArray)

/** Chủ ba → khuyết trọng bác → sóng dội (trọng bác ba) → tâm trương. */
object WaveformSynth {
    const val NOTCH_DELAY_MS = 45.0
    const val DICROTIC_DELAY_MS = 80.0
    const val RELAX_DELAY_MS = 140.0
    const val END_DELAY_MS = 200.0
    /** Thân sóng chiếm tối đa tỉ lệ này của chu kỳ để nhịp sau không chồng lên. */
    const val MAX_FILL = 0.85

    fun synth(p: PulseProfile, intervalMs: Double): BeatWaveform {
        val up = p.upstrokeMs
        val dic = p.dicroticRatio
        val notch = minOf(0.25, dic * 0.6)
        var pts = listOf(
            EnvelopePoint(0.0, 0.0),
            EnvelopePoint(up * 0.5, 0.45),
            EnvelopePoint(up, 1.0),
            EnvelopePoint(up + NOTCH_DELAY_MS, notch),
            EnvelopePoint(up + DICROTIC_DELAY_MS, dic),
            EnvelopePoint(up + RELAX_DELAY_MS, dic * 0.2),
            EnvelopePoint(up + END_DELAY_MS, 0.0)
        )
        val total = pts.last().tMs
        val limit = intervalMs * MAX_FILL
        if (total > limit && limit > 0) {
            val k = limit / total
            pts = pts.map { EnvelopePoint(it.tMs * k, it.level) }
        }
        return BeatWaveform(pts)
    }
}
