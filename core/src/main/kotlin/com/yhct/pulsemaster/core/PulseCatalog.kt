package com.yhct.pulsemaster.core

/** Nạp và kiểm tra nghiêm ngặt `tcm_28_pulses_haptic.json`. Lỗi dữ liệu -> [PulseDataException]. */
class PulseCatalog private constructor(val schemaVersion: String, val pulses: List<PulseProfile>) {
    private val index = pulses.associateBy { it.id }

    fun byId(id: String): PulseProfile? = index[id]
    fun require(id: String): PulseProfile = index[id] ?: throw PulseDataException("Không có mạch '$id'")

    companion object {
        const val SUPPORTED_MAJOR = 1

        fun parse(json: String): PulseCatalog {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw PulseDataException("Gốc JSON phải là object")
            val version = root["schema_version"] as? String ?: throw PulseDataException("Thiếu schema_version")
            val major = version.substringBefore('.').toIntOrNull()
                ?: throw PulseDataException("schema_version không hợp lệ: $version")
            if (major != SUPPORTED_MAJOR) throw PulseDataException("Không hỗ trợ schema_version $version")
            val list = root["pulses"] as? List<*> ?: throw PulseDataException("Thiếu mảng pulses")
            val out = ArrayList<PulseProfile>(list.size)
            val seen = HashSet<String>()
            for ((i, item) in list.withIndex()) {
                val p = parsePulse(item as? Map<*, *> ?: throw PulseDataException("pulses[$i] không phải object"))
                if (!seen.add(p.id)) throw PulseDataException("Trùng id '${p.id}'")
                out.add(p)
            }
            if (out.isEmpty()) throw PulseDataException("Danh sách mạch rỗng")
            return PulseCatalog(version, out)
        }

        private fun parsePulse(m: Map<*, *>): PulseProfile {
            val id = str(m, "id", "?")
            fun ctx(k: String) = "$id.$k"
            val bpmRange = m["bpm_range"] as? List<*>
            if (bpmRange == null || bpmRange.size != 2) throw PulseDataException("${ctx("bpm_range")} phải có 2 số")
            val lo = (bpmRange[0] as? Double) ?: throw PulseDataException("${ctx("bpm_range")}[0] không phải số")
            val hi = (bpmRange[1] as? Double) ?: throw PulseDataException("${ctx("bpm_range")}[1] không phải số")
            if (lo < 20 || hi > 220 || lo > hi) throw PulseDataException("${ctx("bpm_range")} ngoài [20,220] hoặc đảo ngược")
            val def = num(m, "default_bpm", id)
            if (def < lo || def > hi) throw PulseDataException("${ctx("default_bpm")} nằm ngoài bpm_range")

            val da = m["depth_amplitude"] as? Map<*, *> ?: throw PulseDataException("${ctx("depth_amplitude")} thiếu")
            val depth = DepthAmplitude(
                unit(da, "phu", "$id.depth_amplitude"),
                unit(da, "trung", "$id.depth_amplitude"),
                unit(da, "tram", "$id.depth_amplitude")
            )
            val re = m["region_emphasis"] as? Map<*, *> ?: throw PulseDataException("${ctx("region_emphasis")} thiếu")
            val regions = RegionWeights(
                unit(re, "thon", "$id.region_emphasis"),
                unit(re, "quan", "$id.region_emphasis"),
                unit(re, "xich", "$id.region_emphasis")
            )
            val rhythmStr = str(m, "rhythm_type", id)
            val rhythm = RhythmType.fromJson(rhythmStr)
                ?: throw PulseDataException("${ctx("rhythm_type")} lạ: '$rhythmStr'")

            var pauseP = 0.0
            var pauseN = 0
            if (rhythm == RhythmType.INTERMITTENT_IRREGULAR) {
                pauseP = unit(m, "pause_probability", id)
            }
            if (rhythm == RhythmType.INTERMITTENT_REGULAR) {
                val n = num(m, "pause_every_n_beats", id)
                if (n < 1 || n > 255 || n != Math.floor(n)) {
                    throw PulseDataException("${ctx("pause_every_n_beats")} phải là số nguyên 1..255")
                }
                pauseN = n.toInt()
            }
            val upstroke = num(m, "upstroke_ms", id)
            if (upstroke < 5 || upstroke > 200) throw PulseDataException("${ctx("upstroke_ms")} ngoài [5,200]")

            return PulseProfile(
                id = id,
                nameVi = str(m, "name_vi", id),
                nameHan = str(m, "name_han", id),
                category = str(m, "category", id),
                bpmMin = lo, bpmMax = hi, defaultBpm = def,
                depthAmplitude = depth,
                sharpness = unit(m, "sharpness", id),
                upstrokeMs = upstroke,
                dicroticRatio = unit(m, "dicrotic_ratio", id),
                rhythm = rhythm,
                pauseProbability = pauseP,
                pauseEveryNBeats = pauseN,
                regionEmphasis = regions,
                clinicalFeel = str(m, "clinical_feel", id)
            )
        }

        private fun str(m: Map<*, *>, k: String, id: String): String {
            val v = m[k] as? String
            if (v.isNullOrBlank()) throw PulseDataException("$id.$k thiếu hoặc rỗng")
            return v
        }

        private fun num(m: Map<*, *>, k: String, id: String): Double {
            val v = m[k] as? Double ?: throw PulseDataException("$id.$k thiếu hoặc không phải số")
            if (v.isNaN() || v.isInfinite()) throw PulseDataException("$id.$k không hữu hạn")
            return v
        }

        private fun unit(m: Map<*, *>, k: String, ctx: String): Double {
            val v = num(m, k, ctx)
            if (v < 0.0 || v > 1.0) throw PulseDataException("$ctx.$k = $v ngoài [0,1]")
            return v
        }
    }
}
