package com.yhct.pulsemaster.core

data class BeatPlan(
    val beat: Beat,
    /** Đỉnh biên độ 0..1 (đã nhân trọng số bộ và suy giảm). 0 nếu nhịp bị bỏ hoặc không có ngón nào chạm. */
    val peak: Double,
    val waveform: BeatWaveform,
    /** Bộ có đóng góp lớn nhất (để UI tô sáng). */
    val dominantRegion: Region?
) {
    val intervalMs: Double get() = beat.intervalMs
    val silent: Boolean get() = beat.silent || peak <= 0.0
}

/** Lõi dùng chung cho mọi transport: lực từng bộ + hồ sơ mạch -> kế hoạch một nhịp. */
class PulsePlayback(
    val profile: PulseProfile,
    bpm: Double = profile.defaultBpm,
    uniform: UniformSource,
    private val mode: InterpolationMode = InterpolationMode.MONOTONE_CUBIC
) {
    private val scheduler = BeatScheduler(profile, bpm, uniform)
    val bpm: Double get() = scheduler.bpm

    /** [forces]: lực chuẩn hóa của các bộ đang có ngón tay. Một động cơ rung nên lấy bộ mạnh nhất. */
    fun planBeat(forces: Map<Region, Double>): BeatPlan {
        val touching = forces.filter { it.value >= Zones.NONE_MAX }
        val maxForce = touching.values.maxOrNull() ?: 0.0
        val beat = scheduler.next(maxForce)
        var best = 0.0
        var bestRegion: Region? = null
        for ((r, f) in touching) {
            val amp = DepthInterpolator.amplitudeAt(profile.depthAmplitude, f, mode) * profile.regionEmphasis.of(r)
            if (amp > best) { best = amp; bestRegion = r }
        }
        val peak = if (beat.silent) 0.0 else (best * beat.amplitudeScale).coerceIn(0.0, 1.0)
        return BeatPlan(beat, peak, WaveformSynth.synth(profile, beat.intervalMs), bestRegion)
    }
}
