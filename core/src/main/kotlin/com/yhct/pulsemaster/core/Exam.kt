package com.yhct.pulsemaster.core

data class ExamPoint(val hand: Hand, val region: Region, val depth: Depth) {
    val label: String get() = "${hand.label} · ${region.label} · ${depth.label}"
}

object ExamPlan {
    /** 18 điểm: mỗi tay (trái rồi phải) × Thốn–Quan–Xích × Phù–Trung–Trầm. */
    val points: List<ExamPoint> = Hand.entries.flatMap { h ->
        Region.entries.flatMap { r ->
            listOf(Depth.PHU, Depth.TRUNG, Depth.TRAM).map { d -> ExamPoint(h, r, d) }
        }
    }
}

/**
 * Chỉ coi là đạt khi giữ đúng tầng đủ [requiredBeats] nhịp LIÊN TIẾP.
 * Mỗi nhịp được tính khi tổng thời gian ngoài tầng trong nhịp đó ≤ [graceMs]; nếu không thì chuỗi về 0.
 * Nhấc ngón tay ([onLift]) cũng đặt lại chuỗi. Nhịp bị bỏ (mạch Xúc/Kết/Đại) vẫn tính là một nhịp thời gian.
 */
class HoldTracker(
    private val target: Depth,
    private val requiredBeats: Int = 5,
    private val graceMs: Long = 100L,
    private val maxSampleGapMs: Long = 100L
) {
    init { require(target != Depth.NONE) { "Tầng mục tiêu không thể là NONE" } }

    var consecutive = 0
        private set
    var completed = false
        private set
    private var outMs = 0L
    private var lastT = -1L
    private var lastInZone = false

    fun onSample(depth: Depth, tMs: Long) {
        if (lastT >= 0 && !lastInZone) outMs += (tMs - lastT).coerceIn(0L, maxSampleGapMs)
        lastInZone = depth == target
        lastT = tMs
    }

    /** Gọi mỗi nhịp (kể cả nhịp bỏ). Trả về true khi vừa đạt. */
    fun onBeat(tMs: Long): Boolean {
        if (completed) return false
        if (lastT >= 0 && !lastInZone) outMs += (tMs - lastT).coerceIn(0L, maxSampleGapMs)
        lastT = tMs
        if (lastT >= 0 && outMs <= graceMs && lastInZone) consecutive++ else consecutive = 0
        outMs = 0L
        if (consecutive >= requiredBeats) { completed = true; return true }
        return false
    }

    fun onLift() { consecutive = 0; outMs = 0L; lastT = -1L; lastInZone = false }
    val required: Int get() = requiredBeats
}

data class ExamResult(val point: ExamPoint, val captured: Boolean, val meanForce: Double)

class ExamSession(private val plan: List<ExamPoint> = ExamPlan.points) {
    private val results = ArrayList<ExamResult>()
    val index: Int get() = results.size
    val total: Int get() = plan.size
    val isFinished: Boolean get() = results.size >= plan.size
    val current: ExamPoint? get() = plan.getOrNull(results.size)
    val captured: List<ExamResult> get() = results.toList()

    fun record(meanForce: Double, captured: Boolean = true) {
        val p = current ?: throw IllegalStateException("Phiên khám đã kết thúc")
        results.add(ExamResult(p, captured, meanForce))
    }

    fun skip() = record(0.0, captured = false)

    fun summary(): String = buildString {
        results.forEach { appendLine("${it.point.label}: ${if (it.captured) "đạt (F≈%.2f)".format(it.meanForce) else "bỏ qua"}") }
    }
}
