package com.yhct.pulsemaster.app

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yhct.pulsemaster.core.*

/**
 * Khám 18 điểm (2 tay × Thốn–Quan–Xích × Phù–Trung–Trầm). Một điểm chỉ được ghi nhận khi người học
 * giữ đúng tầng lực ≥ 5 nhịp liên tiếp. Dùng chung logic cho cả điện thoại lẫn đệm BLE qua [PulseHardwareTransport].
 */
class ExamActivity : Activity(), PadTouchListener, PulseTransportListener {

    private lateinit var catalog: PulseCatalog
    private lateinit var store: CalibrationStore
    private lateinit var phone: PhoneHapticTransport
    private lateinit var ble: BleWristPadTransport
    private lateinit var active: PulseHardwareTransport
    private lateinit var profile: PulseProfile

    private var session = ExamSession()
    private var hold: HoldTracker? = null
    private val heldForces = ArrayList<Double>()

    private lateinit var statusText: TextView
    private lateinit var transportText: TextView
    private lateinit var feelText: TextView
    private lateinit var gauge: ForceGaugeView
    private lateinit var wave: PulseWaveView
    private val pads = HashMap<Region, PulsePadView>()
    private var wantBle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val r = PulseRepository.load(this)) {
            is CatalogResult.Error -> {
                setContentView(column(20).apply { addView(label("Không thể khám: ${r.message}", 16f, color = Color.rgb(198, 40, 40))) })
                return
            }
            is CatalogResult.Ok -> catalog = r.catalog
        }
        store = CalibrationStore(this)
        phone = PhoneHapticTransport(this, store.load())
        ble = BleWristPadTransport(this)
        active = phone
        profile = catalog.pulses.first()
        buildUi()
        startNewSession()
    }

    private fun buildUi() {
        val root = column(12)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            catalog.pulses.map { "${it.nameVi}  ${it.nameHan}" })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                profile = catalog.pulses[pos]
                feelText.text = "${profile.category} · ${profile.clinicalFeel}"
                active.setProfile(profile, profile.defaultBpm)
                wave.clear()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        root.addView(spinner, spinner.lp())
        feelText = label("", 12f, color = Color.GRAY).also { root.addView(it) }

        val tr = row()
        tr.addView(button("Điện thoại") { wantBle = false; selectTransport() }, tr.lp(w = 0, weight = 1f))
        tr.addView(button("Đệm BLE") { requestBle() }, tr.lp(w = 0, weight = 1f))
        root.addView(tr, tr.lp())
        transportText = label("", 12f, color = Color.DKGRAY).also { root.addView(it) }

        statusText = label("", 16f, bold = true).also { root.addView(it, it.lp(margin = dp(4))) }

        val body = row()
        gauge = ForceGaugeView(this)
        body.addView(gauge, LinearLayout.LayoutParams(dp(80), dp(280)))
        for (r in Region.entries) {
            val pad = PulsePadView(this, r).also { it.touchListener = this; pads[r] = it }
            body.addView(pad, LinearLayout.LayoutParams(0, dp(280), 1f))
        }
        root.addView(body, body.lp())

        wave = PulseWaveView(this)
        root.addView(wave, wave.lp(h = dp(130), margin = dp(4)))

        val actions = row()
        actions.addView(button("Bỏ qua điểm") { skipPoint() }, actions.lp(w = 0, weight = 1f))
        actions.addView(button("Làm lại") { startNewSession() }, actions.lp(w = 0, weight = 1f))
        actions.addView(button("Kết quả") { showSummary() }, actions.lp(w = 0, weight = 1f))
        root.addView(actions, actions.lp())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---- Transport ------------------------------------------------------------------------------

    private fun selectTransport() {
        active.stopFeedback(); active.close(); active.listener = null
        if (!wantBle) phone.setCalibration(store.load())
        active = if (wantBle) ble else phone
        active.listener = this
        active.setProfile(profile, profile.defaultBpm)
        active.open()
        wave.clear()
    }

    private fun requestBle() {
        if (!ble.hasPermissions()) {
            requestPermissions(ble.requiredPermissions(), REQ_BLE)
        } else { wantBle = true; selectTransport() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            wantBle = true; selectTransport()
        } else {
            transportText.text = "Chưa được cấp quyền Bluetooth – không thể kết nối đệm."
            transportText.setTextColor(Color.rgb(198, 40, 40))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::catalog.isInitialized) selectTransport()
    }

    override fun onPause() {
        super.onPause()
        if (::catalog.isInitialized) { active.stopFeedback(); active.close(); active.listener = null }
    }

    // ---- Cảm ứng từ các pad (chỉ có ý nghĩa với chế độ điện thoại) ----------------------------------

    override fun onPadTouch(region: Region, event: MotionEvent) {
        if (active === phone) phone.onTouch(region, event)
    }

    override fun onPadRelease(region: Region) {
        if (active === phone) phone.onRelease(region)
    }

    // ---- Sự kiện từ transport -----------------------------------------------------------------------

    override fun onStateChanged(state: TransportState) {
        val (msg, color) = when (state) {
            TransportState.Idle -> "Chưa kết nối" to Color.GRAY
            TransportState.Connecting -> "Đang tìm và kết nối đệm…" to Color.rgb(239, 108, 0)
            TransportState.Ready -> (if (active === ble) "Đã kết nối đệm 3 kênh (BLE)" else "Dùng động cơ rung của điện thoại") to Color.rgb(46, 125, 50)
            is TransportState.Failed -> "Lỗi: ${state.reason}" to Color.rgb(198, 40, 40)
        }
        transportText.text = msg
        transportText.setTextColor(color)
        if (state == TransportState.Ready) active.startFeedback(Region.entries.toSet())
    }

    override fun onForce(region: Region, force: Double, depth: Depth, tMs: Long) {
        pads[region]?.depth = depth
        val pt = session.current ?: return
        if (region != pt.region) return
        gauge.force = force
        gauge.inZone = depth == pt.depth
        hold?.onSample(depth, tMs)
        if (depth == pt.depth) heldForces.add(force) else heldForces.clear()
    }

    override fun onBeat(event: BeatEvent) {
        wave.addBeat(event)
        val pt = session.current ?: return
        val h = hold ?: return
        if (h.onBeat(event.startMs)) capture(pt)
        refreshStatus()
    }

    // ---- Phiên khám -----------------------------------------------------------------------------------

    private fun startNewSession() {
        session = ExamSession()
        heldForces.clear()
        prepareCurrent()
    }

    private fun prepareCurrent() {
        val pt = session.current
        hold = pt?.let { HoldTracker(it.depth) }
        gauge.target = pt?.depth
        gauge.inZone = false
        for ((r, p) in pads) p.highlighted = pt?.region == r
        refreshStatus()
    }

    private fun capture(pt: ExamPoint) {
        val mean = if (heldForces.isEmpty()) 0.0 else heldForces.average()
        session.record(mean, captured = true)
        heldForces.clear()
        Toast.makeText(this, "Đã ghi nhận: ${pt.label}", Toast.LENGTH_SHORT).show()
        prepareCurrent()
        if (session.isFinished) showSummary()
    }

    private fun skipPoint() {
        if (session.isFinished) return
        session.skip()
        heldForces.clear()
        prepareCurrent()
    }

    private fun refreshStatus() {
        val pt = session.current
        statusText.text = if (pt == null) "Hoàn tất ${session.total}/${session.total} điểm"
        else "Điểm ${session.index + 1}/${session.total}: ${pt.label}\nGiữ đúng tầng: ${hold?.consecutive ?: 0}/${hold?.required ?: 5} nhịp"
    }

    private fun showSummary() {
        AlertDialog.Builder(this)
            .setTitle("Kết quả khám (${session.captured.count { it.captured }}/${session.total} đạt)")
            .setMessage(session.summary().ifBlank { "Chưa có điểm nào." })
            .setPositiveButton("Đóng", null)
            .show()
    }

    private companion object { const val REQ_BLE = 42 }
}
