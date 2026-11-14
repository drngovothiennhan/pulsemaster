package com.yhct.pulsemaster.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import com.yhct.pulsemaster.core.*

/**
 * Chế độ Mobile Native: lực từ MotionEvent (pressure + size, hợp nhất qua [FingerCalibration]),
 * phản hồi bằng VibrationEffect.createWaveform. Mọi hàm gọi trên main thread.
 *
 * Giới hạn phần cứng (không khắc phục được bằng phần mềm): chỉ có 1 động cơ rung, nên khi nhiều ngón
 * cùng chạm sẽ phát bộ có biên độ lớn nhất; máy không có điều khiển biên độ chỉ phát xung gần đúng.
 */
class PhoneHapticTransport(context: Context, private var calibration: FingerCalibration) : PulseHardwareTransport {

    override val kind = TransportKind.PHONE_INTERNAL_HAPTIC
    override var state: TransportState = TransportState.Idle
        private set
    override var listener: PulseTransportListener? = null

    val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true
    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val handler = Handler(Looper.getMainLooper())
    private var playback: PulsePlayback? = null
    private val forces = HashMap<Region, Double>()
    private val filters = Region.entries.associateWith { ForceFilter() }
    private val trackers = Region.entries.associateWith { DepthTracker() }
    private var feedbackEnabled = true
    private var running = false
    private var nextBeatAt = 0L
    private val beatRunnable = Runnable { onBeatTick() }

    fun setCalibration(c: FingerCalibration) { calibration = c }

    override fun open() {
        setState(
            if (hasVibrator) TransportState.Ready
            else TransportState.Failed("Thiết bị không có động cơ rung")
        )
    }

    override fun close() {
        stopFeedback()
        setState(TransportState.Idle)
    }

    override fun setProfile(profile: PulseProfile, bpm: Double) {
        playback = PulsePlayback(profile, bpm, SeededUniform(System.nanoTime()))
    }

    override fun startFeedback(regions: Set<Region>) {
        feedbackEnabled = true
        if (forces.isNotEmpty()) startLoop()
    }

    override fun stopFeedback() {
        feedbackEnabled = false
        stopLoop()
    }

    /** Gọi từ PulsePadView cho ACTION_DOWN / ACTION_MOVE. */
    fun onTouch(region: Region, event: MotionEvent) {
        val raw = RawSample(event.getPressure(0).toDouble(), event.getSize(0).toDouble())
        val f = filters.getValue(region).update(calibration.fuse(raw))
        val depth = trackers.getValue(region).update(f)
        forces[region] = f
        val now = SystemClock.uptimeMillis()
        listener?.onForce(region, f, depth, now)
        if (depth != Depth.NONE) startLoop()
    }

    /** Gọi khi nhấc ngón (ACTION_UP / ACTION_CANCEL). */
    fun onRelease(region: Region) {
        forces.remove(region)
        filters.getValue(region).reset()
        trackers.getValue(region).reset()
        listener?.onForce(region, 0.0, Depth.NONE, SystemClock.uptimeMillis())
        if (forces.isEmpty()) stopLoop()
    }

    private fun startLoop() {
        if (running || !feedbackEnabled || playback == null || state != TransportState.Ready) return
        running = true
        nextBeatAt = SystemClock.uptimeMillis()
        handler.post(beatRunnable)
    }

    private fun stopLoop() {
        running = false
        handler.removeCallbacks(beatRunnable)
        vibrator?.cancel()
    }

    private fun onBeatTick() {
        if (!running) return
        val pb = playback ?: run { stopLoop(); return }
        val now = SystemClock.uptimeMillis()
        val plan = pb.planBeat(HashMap(forces))
        if (!plan.silent) vibrate(plan)
        listener?.onBeat(BeatEvent(now, plan, plan.peak, plan.silent, plan.intervalMs))

        // Lên lịch theo thời điểm tuyệt đối để không trôi nhịp; nếu bị trễ nhiều thì bám lại đồng hồ.
        nextBeatAt += Math.round(plan.intervalMs)
        if (nextBeatAt < now) nextBeatAt = now + Math.round(plan.intervalMs)
        handler.postAtTime(beatRunnable, nextBeatAt)
    }

    private fun vibrate(plan: BeatPlan) {
        val v = vibrator ?: return
        try {
            val effect = if (v.hasAmplitudeControl()) {
                val steps = plan.waveform.toVibrationSteps(plan.peak, STEP_MS)
                if (steps.amplitudes.all { it == 0 }) return
                VibrationEffect.createWaveform(steps.timings, steps.amplitudes, -1)
            } else {
                // Không có điều khiển biên độ: chỉ điều chỉnh được độ dài xung (xấp xỉ).
                VibrationEffect.createOneShot((20 + 60 * plan.peak).toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Hiệu ứng rung không hợp lệ", e)
            setState(TransportState.Failed("Hiệu ứng rung bị từ chối: ${e.message}"))
            stopLoop()
        }
    }

    private fun setState(s: TransportState) {
        state = s
        listener?.onStateChanged(s)
    }

    private companion object {
        const val TAG = "PhoneHaptic"
        const val STEP_MS = 10
    }
}
