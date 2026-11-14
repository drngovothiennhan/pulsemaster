package com.yhct.pulsemaster.core

/** Tầng lực ấn. Ngưỡng nằm trong [Zones]. */
enum class Depth(val label: String) {
    NONE("Chưa chạm"),
    PHU("Phù (Ấn nhẹ)"),
    TRUNG("Trung (Ấn vừa)"),
    TRAM("Trầm (Ấn mạnh)")
}

/** Ba bộ của một cổ tay. [jsonKey] khớp khóa trong `region_emphasis`. */
enum class Region(val jsonKey: String, val label: String, val channel: Int) {
    THON("thon", "Thốn", 0),
    QUAN("quan", "Quan", 1),
    XICH("xich", "Xích", 2)
}

enum class Hand(val label: String) {
    LEFT("Tay trái"),
    RIGHT("Tay phải")
}

/** [json] là giá trị trong `rhythm_type`; [wireCode] là mã gói BLE (xem docs/BLE_PROTOCOL.md). */
enum class RhythmType(val json: String, val wireCode: Int) {
    REGULAR("regular", 0),
    INTERMITTENT_IRREGULAR("intermittent_irregular", 1),
    INTERMITTENT_REGULAR("intermittent_regular", 2),
    IRREGULAR_FADE("irregular_fade", 3),
    SLIGHTLY_UNEVEN("slightly_uneven", 4);

    companion object {
        fun fromJson(s: String): RhythmType? = entries.firstOrNull { it.json == s }
        fun fromWire(code: Int): RhythmType? = entries.firstOrNull { it.wireCode == code }
    }
}

data class DepthAmplitude(val phu: Double, val trung: Double, val tram: Double) {
    fun of(d: Depth): Double = when (d) {
        Depth.NONE -> 0.0
        Depth.PHU -> phu
        Depth.TRUNG -> trung
        Depth.TRAM -> tram
    }
}

data class RegionWeights(val thon: Double, val quan: Double, val xich: Double) {
    fun of(r: Region): Double = when (r) {
        Region.THON -> thon
        Region.QUAN -> quan
        Region.XICH -> xich
    }
}

data class PulseProfile(
    val id: String,
    val nameVi: String,
    val nameHan: String,
    val category: String,
    val bpmMin: Double,
    val bpmMax: Double,
    val defaultBpm: Double,
    val depthAmplitude: DepthAmplitude,
    val sharpness: Double,
    val upstrokeMs: Double,
    val dicroticRatio: Double,
    val rhythm: RhythmType,
    /** Chỉ có nghĩa với [RhythmType.INTERMITTENT_IRREGULAR]. */
    val pauseProbability: Double,
    /** Chỉ có nghĩa với [RhythmType.INTERMITTENT_REGULAR]; 0 nếu không dùng. */
    val pauseEveryNBeats: Int,
    val regionEmphasis: RegionWeights,
    val clinicalFeel: String
)

class PulseDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Maths {
    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
