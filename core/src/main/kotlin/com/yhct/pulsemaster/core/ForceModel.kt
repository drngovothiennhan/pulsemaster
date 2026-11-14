package com.yhct.pulsemaster.core

object Zones {
    const val NONE_MAX = 0.05
    const val PHU_MAX = 0.35
    const val TRUNG_MAX = 0.70
    /** Tâm mỗi tầng: điểm neo để nội suy biên độ. */
    const val CENTER_PHU = 0.20
    const val CENTER_TRUNG = 0.525
    const val CENTER_TRAM = 0.85
    const val HYSTERESIS = 0.02

    val boundaries = doubleArrayOf(NONE_MAX, PHU_MAX, TRUNG_MAX)

    fun classify(f: Double): Depth = when {
        f < NONE_MAX -> Depth.NONE
        f < PHU_MAX -> Depth.PHU
        f <= TRUNG_MAX -> Depth.TRUNG
        else -> Depth.TRAM
    }

    /** Khoảng [lo, hi] của một tầng, dùng cho vùng mục tiêu trên thanh lực. */
    fun range(d: Depth): ClosedFloatingPointRange<Double> = when (d) {
        Depth.NONE -> 0.0..NONE_MAX
        Depth.PHU -> NONE_MAX..PHU_MAX
        Depth.TRUNG -> PHU_MAX..TRUNG_MAX
        Depth.TRAM -> TRUNG_MAX..1.0
    }
}

/** Tín hiệu thô từ màn hình: [pressure] (MotionEvent.getPressure / UITouch.force chuẩn hóa) và [size] (getSize / majorRadius). */
data class RawSample(val pressure: Double, val size: Double)

class CalibrationException(message: String) : Exception(message)

/**
 * Ánh xạ tuyến tính từng đoạn qua 3 neo cá nhân: light→0.20, medium→0.525, hard→0.85,
 * ngoại suy theo độ dốc của đoạn đầu/cuối rồi kẹp vào [0,1].
 */
class ChannelCalibration(val light: Double, val medium: Double, val hard: Double) {
    init {
        if (!light.isFinite() || !medium.isFinite() || !hard.isFinite()) {
            throw CalibrationException("Giá trị hiệu chuẩn không hữu hạn")
        }
        if (hard <= 0.0) throw CalibrationException("Mức ấn mạnh phải > 0")
        val minGap = MIN_GAP_RATIO * hard
        if (medium - light < minGap || hard - medium < minGap) {
            throw CalibrationException("Ba mức ấn chưa đủ khác biệt: hãy chạm nhẹ hơn và nhấn mạnh hơn rõ rệt")
        }
    }

    fun normalize(raw: Double): Double {
        val x = Zones.CENTER_PHU
        val y = Zones.CENTER_TRUNG
        val z = Zones.CENTER_TRAM
        val f = when {
            raw <= light -> x + (raw - light) * ((y - x) / (medium - light))
            raw <= medium -> x + (raw - light) / (medium - light) * (y - x)
            raw <= hard -> y + (raw - medium) / (hard - medium) * (z - y)
            else -> z + (raw - hard) * ((z - y) / (hard - medium))
        }
        return f.coerceIn(0.0, 1.0)
    }

    companion object { const val MIN_GAP_RATIO = 0.04 }
}

/** Hiệu chuẩn một ngón tay: ít nhất một kênh hợp lệ. Kênh pressure null nếu thiết bị không có cảm biến lực thật. */
class FingerCalibration(val pressure: ChannelCalibration?, val size: ChannelCalibration?) {
    init {
        if (pressure == null && size == null) {
            throw CalibrationException("Không nhận được thay đổi lực nào từ màn hình. Thử chạm lại rõ ràng hơn.")
        }
    }

    fun fuse(raw: RawSample): Double {
        val fp = pressure?.normalize(raw.pressure)
        val fs = size?.normalize(raw.size)
        return when {
            fp != null && fs != null -> (PRESSURE_WEIGHT * fp + (1 - PRESSURE_WEIGHT) * fs).coerceIn(0.0, 1.0)
            fp != null -> fp
            else -> fs!!
        }
    }

    fun toJson(): String {
        fun ch(c: ChannelCalibration?) =
            if (c == null) "null" else "{\"light\":${c.light},\"medium\":${c.medium},\"hard\":${c.hard}}"
        return "{\"v\":1,\"pressure\":${ch(pressure)},\"size\":${ch(size)}}"
    }

    companion object {
        const val PRESSURE_WEIGHT = 0.6

        /** Mặc định trước khi hiệu chuẩn: chỉ kênh size, neo do nền tảng cung cấp. */
        fun defaultSizeOnly(light: Double, medium: Double, hard: Double) =
            FingerCalibration(null, ChannelCalibration(light, medium, hard))

        fun fromJson(json: String): FingerCalibration {
            val m = MiniJson.parse(json) as? Map<*, *> ?: throw CalibrationException("Hiệu chuẩn lưu sai định dạng")
            if ((m["v"] as? Double)?.toInt() != 1) throw CalibrationException("Phiên bản hiệu chuẩn không hỗ trợ")
            fun ch(k: String): ChannelCalibration? {
                val o = m[k] ?: return null
                val c = o as? Map<*, *> ?: throw CalibrationException("Kênh $k sai định dạng")
                fun d(n: String) = c[n] as? Double ?: throw CalibrationException("Kênh $k thiếu $n")
                return ChannelCalibration(d("light"), d("medium"), d("hard"))
            }
            return FingerCalibration(ch("pressure"), ch("size"))
        }
    }
}

enum class CalibrationPhase(val label: String) {
    LIGHT("Chạm nhẹ (Phù)"),
    MEDIUM("Nhấn vừa (Trung)"),
    HARD("Nhấn mạnh (Trầm)")
}

/** Thu mẫu trong 3 pha liên tiếp (mặc định tổng 5 giây) rồi tính hiệu chuẩn bằng trung vị. */
class CalibrationRecorder(
    private val totalDurationMs: Long = 5000L,
    private val minSamplesPerPhase: Int = 8
) {
    private val phaseMs = totalDurationMs / 3
    private var startMs = -1L
    private val samples = Array(3) { ArrayList<RawSample>() }

    fun start(nowMs: Long) {
        startMs = nowMs
        samples.forEach { it.clear() }
    }

    /** Pha hiện tại, hoặc null nếu chưa bắt đầu / đã hết giờ. */
    fun phaseAt(nowMs: Long): CalibrationPhase? {
        if (startMs < 0) return null
        val elapsed = nowMs - startMs
        if (elapsed < 0 || elapsed >= totalDurationMs) return null
        return CalibrationPhase.entries[(elapsed / phaseMs).toInt().coerceAtMost(2)]
    }

    fun progress(nowMs: Long): Double =
        if (startMs < 0) 0.0 else ((nowMs - startMs).toDouble() / totalDurationMs).coerceIn(0.0, 1.0)

    fun isFinished(nowMs: Long) = startMs >= 0 && nowMs - startMs >= totalDurationMs

    /** Chỉ gọi khi ngón tay đang chạm. Trả về pha đã ghi, hoặc null nếu ngoài khoảng thu. */
    fun addSample(nowMs: Long, raw: RawSample): CalibrationPhase? {
        if (!raw.pressure.isFinite() || !raw.size.isFinite()) return null
        val ph = phaseAt(nowMs) ?: return null
        samples[ph.ordinal].add(raw)
        return ph
    }

    fun finish(): FingerCalibration {
        val per = samples.mapIndexed { i, list ->
            if (list.size < minSamplesPerPhase) {
                throw CalibrationException(
                    "Pha ${CalibrationPhase.entries[i].label}: chỉ có ${list.size} mẫu (cần ≥ $minSamplesPerPhase). " +
                        "Hãy giữ ngón tay chạm liên tục trong suốt 5 giây."
                )
            }
            list.drop((list.size * SETTLE_FRACTION).toInt()) // bỏ phần đầu lúc ngón chưa ổn định
        }
        fun channel(sel: (RawSample) -> Double): ChannelCalibration? = try {
            ChannelCalibration(
                median(per[0].map(sel)), median(per[1].map(sel)), median(per[2].map(sel))
            )
        } catch (e: CalibrationException) { null }

        return FingerCalibration(channel { it.pressure }, channel { it.size })
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    companion object { const val SETTLE_FRACTION = 0.3 }
}

/** Lọc EMA chống nhiễu cảm biến. */
class ForceFilter(private val alpha: Double = 0.35) {
    private var v: Double? = null
    fun update(x: Double): Double {
        val cur = v
        val n = if (cur == null) x else cur + alpha * (x - cur)
        v = n
        return n
    }
    fun reset() { v = null }
}

/** Phân tầng có trễ (hysteresis ±[Zones.HYSTERESIS]) để không nhấp nháy ở biên. */
class DepthTracker(private val hysteresis: Double = Zones.HYSTERESIS) {
    private var level = 0
    val depth: Depth get() = Depth.entries[level]

    fun update(force: Double): Depth {
        val b = Zones.boundaries
        while (level < 3 && force >= b[level] + hysteresis) level++
        while (level > 0 && force <= b[level - 1] - hysteresis) level--
        return depth
    }

    fun reset() { level = 0 }
}
