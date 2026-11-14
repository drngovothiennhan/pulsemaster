package com.yhct.pulsemaster.core

/** Nguồn ngẫu nhiên đều [0,1). Tách ra để Kotlin/Swift/test dùng chung chuỗi giá trị xác định. */
fun interface UniformSource { fun next(): Double }

class SeededUniform(seed: Long) : UniformSource {
    private val r = kotlin.random.Random(seed)
    override fun next(): Double = r.nextDouble()
}

data class Beat(
    val index: Int,
    /** Thời gian từ nhịp này đến nhịp kế tiếp (ms). */
    val intervalMs: Double,
    /** Nhịp bị bỏ (không rung) nhưng thời gian vẫn trôi. */
    val silent: Boolean,
    /** Hệ số biên độ do mạch Tán/Sáp suy giảm khi ấn sâu; 1.0 nếu không áp dụng. */
    val amplitudeScale: Double
)

/**
 * Sinh nhịp theo `rhythm_type`. Quy ước lấy ngẫu nhiên (để các nền tảng trùng nhau):
 * mỗi lần next() với mạch cần ngẫu nhiên tiêu thụ ĐÚNG MỘT giá trị u từ nguồn.
 *  - intermittent_irregular: bỏ nhịp nếu u < pause_probability, trừ nhịp đầu và ngay sau một nhịp đã bỏ.
 *  - intermittent_regular: không dùng ngẫu nhiên; nhịp có index % (n+1) == n bị bỏ (n nhịp, nghỉ 1).
 *  - irregular_fade / slightly_uneven: chu kỳ nhân (1 + (2u-1)·0.12) và biên độ giảm khi ấn sâu.
 */
class BeatScheduler(
    private val profile: PulseProfile,
    bpm: Double = profile.defaultBpm,
    private val uniform: UniformSource
) {
    val bpm: Double = bpm.coerceIn(MIN_BPM, MAX_BPM)
    private var index = 0
    private var lastSilent = false

    fun next(force: Double): Beat {
        val base = 60_000.0 / bpm
        var interval = base
        var silent = false
        var scale = 1.0
        val i = index++

        when (profile.rhythm) {
            RhythmType.REGULAR -> Unit
            RhythmType.INTERMITTENT_IRREGULAR -> {
                val u = uniform.next()
                silent = i > 0 && !lastSilent && u < profile.pauseProbability
            }
            RhythmType.INTERMITTENT_REGULAR -> {
                val n = profile.pauseEveryNBeats
                silent = n > 0 && i % (n + 1) == n
            }
            RhythmType.IRREGULAR_FADE, RhythmType.SLIGHTLY_UNEVEN -> {
                val u = uniform.next()
                interval = base * (1.0 + (2.0 * u - 1.0) * JITTER)
                val fade = if (profile.rhythm == RhythmType.IRREGULAR_FADE) FADE_TAN else FADE_SAP
                scale = 1.0 - fade * Maths.smoothstep(0.5, 1.0, force)
            }
        }
        lastSilent = silent
        return Beat(i, interval, silent, scale)
    }

    companion object {
        const val MIN_BPM = 35.0
        const val MAX_BPM = 180.0
        const val JITTER = 0.12
        /** ĐỀ XUẤT (chưa có trong JSON): mức giảm biên độ tối đa khi ấn sâu. Chủ dự án cần xác nhận. */
        const val FADE_TAN = 0.35
        const val FADE_SAP = 0.15
    }
}
