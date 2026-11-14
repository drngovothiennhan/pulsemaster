package com.yhct.pulsemaster.core

enum class InterpolationMode { LINEAR, MONOTONE_CUBIC }

/**
 * Biên độ liên tục theo lực chuẩn hóa F ∈ [0,1], thay cho nhảy bậc Phù/Trung/Trầm.
 * Neo tại tâm 3 tầng (0.20 / 0.525 / 0.85); ngoài đoạn neo giữ nguyên giá trị đầu/cuối.
 * Monotone cubic (Fritsch–Carlson) không vượt quá giá trị các neo.
 * Dưới ngưỡng chạm (F < 0.10) biên độ giảm mượt về 0.
 */
object DepthInterpolator {
    private const val X0 = Zones.CENTER_PHU
    private const val X1 = Zones.CENTER_TRUNG
    private const val X2 = Zones.CENTER_TRAM

    fun amplitudeAt(a: DepthAmplitude, force: Double, mode: InterpolationMode = InterpolationMode.MONOTONE_CUBIC): Double {
        val f = force.coerceIn(0.0, 1.0)
        val base = when (mode) {
            InterpolationMode.LINEAR -> linear(a, f)
            InterpolationMode.MONOTONE_CUBIC -> pchip(a, f)
        }
        return (base * contactGain(f)).coerceIn(0.0, 1.0)
    }

    fun contactGain(f: Double) = Maths.smoothstep(0.03, 0.10, f)

    private fun linear(a: DepthAmplitude, f: Double): Double = when {
        f <= X0 -> a.phu
        f <= X1 -> a.phu + (f - X0) / (X1 - X0) * (a.trung - a.phu)
        f <= X2 -> a.trung + (f - X1) / (X2 - X1) * (a.tram - a.trung)
        else -> a.tram
    }

    private fun pchip(a: DepthAmplitude, f: Double): Double {
        if (f <= X0) return a.phu
        if (f >= X2) return a.tram
        val h0 = X1 - X0
        val h1 = X2 - X1
        val d0 = (a.trung - a.phu) / h0
        val d1 = (a.tram - a.trung) / h1
        val m1 = if (d0 * d1 <= 0.0) 0.0 else {
            val w1 = 2 * h1 + h0
            val w2 = h1 + 2 * h0
            (w1 + w2) / (w1 / d0 + w2 / d1)
        }
        val m0 = endSlope(h0, h1, d0, d1)
        val m2 = endSlope(h1, h0, d1, d0)
        return if (f <= X1) hermite(f, X0, h0, a.phu, a.trung, m0, m1) else hermite(f, X1, h1, a.trung, a.tram, m1, m2)
    }

    private fun endSlope(h0: Double, h1: Double, d0: Double, d1: Double): Double {
        val m = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        return when {
            Math.signum(m) != Math.signum(d0) -> 0.0
            Math.signum(d0) != Math.signum(d1) && Math.abs(m) > 3 * Math.abs(d0) -> 3 * d0
            else -> m
        }
    }

    private fun hermite(x: Double, xk: Double, h: Double, yk: Double, yk1: Double, mk: Double, mk1: Double): Double {
        val t = (x - xk) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * yk + (t3 - 2 * t2 + t) * h * mk +
            (-2 * t3 + 3 * t2) * yk1 + (t3 - t2) * h * mk1
    }
}
