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
    private var lastRaw: RawSample? = null
    private lateinit var rawText: TextView
    // min/max từng pha để báo lại khi hiệu chuẩn không được: [pha][pMin, pMax, sMin, sMax, số mẫu]
    private val stats = Array(3) { doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 0.0) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = SystemClock.uptimeMillis()
            // Lấy mẫu theo đồng hồ (20 lần/giây) từ giá trị chạm mới nhất: ngón đứng yên sinh rất ít ACTION_MOVE.
            val raw = lastRaw
            if (fingerDown && raw != null) {
                recorder.addSample(now, raw)?.let { ph ->
                    val s = stats[ph.ordinal]
                    s[0] = minOf(s[0], raw.pressure); s[1] = maxOf(s[1], raw.pressure)
                    s[2] = minOf(s[2], raw.size); s[3] = maxOf(s[3], raw.size); s[4] += 1.0
                }
            }
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
        rawText = label("Chạm vào vùng trên để xem thông số mà máy báo.", 12f, color = Color.GRAY).also { col.addView(it) }
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
        for (s in stats) { s[0] = Double.MAX_VALUE; s[1] = -Double.MAX_VALUE; s[2] = Double.MAX_VALUE; s[3] = -Double.MAX_VALUE; s[4] = 0.0 }
        recorder.start(SystemClock.uptimeMillis())
        handler.post(ticker)
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                fingerDown = true
                val raw = RawSample(e.getPressure(0).toDouble(), e.getSize(0).toDouble())
                lastRaw = raw
                rawText.text = "pressure %.3f · size %.3f".format(raw.pressure, raw.size)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fingerDown = false; lastRaw = null; pad.performClick() }
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
            info.text = (e.message ?: "Hiệu chuẩn thất bại") + "\n" + statsText() +
                "\nNếu số đo ba pha gần như giống nhau, máy này không báo lực/diện tích: màn Khám sẽ dùng chế độ kéo dọc."
        }
    }

    private fun statsText(): String = stats.indices.joinToString("\n") { i ->
        val s = stats[i]
        val name = listOf("nhẹ", "vừa", "mạnh")[i]
        if (s[4] == 0.0) "$name: 0 mẫu"
        else "$name: ${s[4].toInt()} mẫu | pressure %.3f–%.3f | size %.3f–%.3f".format(s[0], s[1], s[2], s[3])
    }

    override fun onPause() { super.onPause(); recording = false; handler.removeCallbacks(ticker) }
}
