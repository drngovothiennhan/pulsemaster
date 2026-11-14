package com.yhct.pulsemaster.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var device: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column(20)
        col.addView(label("PulseMaster Pro", 26f, bold = true))
        col.addView(label("Chẩn mạch YHCT – lực ấn Phù · Trung · Trầm", 14f, color = Color.GRAY))
        status = label("", 15f).also { col.addView(it, it.lp(margin = dp(12))) }
        device = label("", 13f, color = Color.GRAY).also { col.addView(it) }

        col.addView(button("1. Hiệu chuẩn ngón tay (5 giây)") {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }, col.lp(margin = dp(6)))
        col.addView(button("2. Khám 18 điểm") {
            startActivity(Intent(this, ExamActivity::class.java))
        }, col.lp(margin = dp(6)))
        val vib = label("", 13f, color = Color.DKGRAY)
        col.addView(button("Thử rung 0,5 giây") {
            vib.text = PhoneHapticTransport(this, CalibrationStore(this).load()).testVibrate()
        }, col.lp(margin = dp(6)))
        col.addView(vib)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = when (val r = PulseRepository.load(this)) {
            is CatalogResult.Ok -> "Đã nạp ${r.catalog.pulses.size} bộ mạch (schema ${r.catalog.schemaVersion})."
            is CatalogResult.Error -> "LỖI: ${r.message}"
        }
        status.setTextColor(if (PulseRepository.load(this) is CatalogResult.Ok) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))

        val probe = PhoneHapticTransport(this, CalibrationStore(this).load())
        val ble = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        device.text = buildString {
            appendLine("Động cơ rung: ${if (probe.hasVibrator) "có" else "KHÔNG"}")
            appendLine("Điều khiển biên độ: ${if (probe.hasAmplitudeControl) "có" else "không (chỉ xung gần đúng)"}")
            appendLine("Bluetooth LE (đệm 3 kênh): ${if (ble) "có" else "không"}")
            append("Hiệu chuẩn ngón tay: ${if (CalibrationStore(this@MainActivity).isCalibrated()) "đã lưu" else "chưa – đang dùng mặc định"}")
        }
    }
}
