package com.yhct.pulsemaster.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.yhct.pulsemaster.core.*

/**
 * Hiệu chuẩn 5 giây: nhẹ (Phù) → vừa (Trung) → mạnh (Trầm). Thu pressure + size, lấy trung vị mỗi pha,
 * lưu neo riêng cho thiết bị và ngón tay. Kênh pressure tự bị loại nếu máy không có cảm biến lực thật.
 */
class CalibrationActivity : Activity() {
    private val recorder = CalibrationRecorder(5000L)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: CalibrationStore
    private lateinit var phaseText: TextView
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pad: View
    private var recording = false
    private var fingerDown = false
    private var lastSampleAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = SystemClock.uptimeMillis()
            progress.progress = (recorder.progress(now) * 1000).toInt()
            val ph = recorder.phaseAt(now)
            if (ph == null) { finishRecording(); return }
            phaseText.text = ph.label + if (!fingerDown) "\n(đặt ngón tay lên vùng bên dưới)" else ""
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CalibrationStore(this)
        val col = column(16)
        col.addView(label("Hiệu chuẩn ngón tay", 22f, bold = true))
        col.addView(label("Bấm Bắt đầu, đặt ngón tay lên vùng bên dưới và giữ chạm liên tục 5 giây: " +
            "~1,7 giây chạm NHẸ, rồi nhấn VỪA, rồi nhấn MẠNH. Làm rõ sự khác biệt giữa ba mức.", 14f, color = Color.GRAY))
        phaseText = label("Sẵn sàng", 20f, bold = true).also { col.addView(it, it.lp(margin = dp(8))) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        col.addView(progress, progress.lp())
        pad = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean = handleTouch(e)
            override fun performClick(): Boolean { super.performClick(); return true }
        }.apply { setBackgroundColor(Color.rgb(232, 245, 233)) }
        col.addView(pad, pad.lp(h = 0, weight = 1f, margin = dp(8)))
        info = label("", 13f, color = Color.DKGRAY).also { col.addView(it) }
        col.addView(button("Bắt đầu") { startRecording() }, col.lp(margin = dp(4)))
        col.addView(button("Xóa hiệu chuẩn đã lưu") {
            store.clear(); info.text = "Đã xóa. Ứng dụng quay về giá trị mặc định."
        }, col.lp(margin = dp(4)))
        setContentView(col)
    }

    private fun startRecording() {
        recording = true
        info.text = ""
        recorder.start(SystemClock.uptimeMillis())
        handler.post(ticker)
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                fingerDown = true
                if (recording) recorder.addSample(SystemClock.uptimeMillis(), RawSample(e.getPressure(0).toDouble(), e.getSize(0).toDouble()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fingerDown = false; pad.performClick() }
            else -> return false
        }
        return true
    }

    private fun finishRecording() {
        recording = false
        progress.progress = 1000
        try {
            val cal = recorder.finish()
            store.save(cal)
            phaseText.text = "Đã lưu hiệu chuẩn"
            info.text = buildString {
                appendLine(if (cal.pressure != null) "Cảm biến lực thật: CÓ (dùng 60% pressure + 40% size)"
                           else "Cảm biến lực thật: KHÔNG – dùng diện tích tiếp xúc (size)")
                cal.size?.let { appendLine("Size: nhẹ %.3f · vừa %.3f · mạnh %.3f".format(it.light, it.medium, it.hard)) }
                cal.pressure?.let { append("Pressure: nhẹ %.3f · vừa %.3f · mạnh %.3f".format(it.light, it.medium, it.hard)) }
            }
        } catch (e: CalibrationException) {
            phaseText.text = "Chưa đạt"
            info.text = e.message ?: "Hiệu chuẩn thất bại"
        }
    }

    override fun onPause() { super.onPause(); recording = false; handler.removeCallbacks(ticker) }
}
