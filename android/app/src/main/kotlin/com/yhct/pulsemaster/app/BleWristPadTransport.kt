package com.yhct.pulsemaster.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.yhct.pulsemaster.core.*
import java.util.ArrayDeque
import java.util.UUID

/**
 * Chế độ Hybrid: đệm cổ tay ESP32-S3 (3 FSR + 3 LRA) qua BLE GATT. Giao thức: docs/BLE_PROTOCOL.md.
 * - Uplink 100 Hz: 6 byte ADC 12-bit (Thốn, Quan, Xích) -> lực chuẩn hóa -> listener.onForce.
 * - Downlink: cấu hình mạch / hiệu chuẩn / START / STOP; MCU tự phát xung (độ trễ < 5 ms, không qua điện thoại).
 * - Thông báo nhịp từ MCU để đồ thị trên điện thoại vẫn đồng bộ với xung thật.
 * Callback GATT chạy trên binder thread; mọi thứ được chuyển về main thread trước khi chạm vào trạng thái.
 */
@SuppressLint("MissingPermission") // đã kiểm tra bằng hasPermissions() trước mỗi thao tác BLE
class BleWristPadTransport(private val context: Context) : PulseHardwareTransport {

    override val kind = TransportKind.EXTERNAL_WRIST_PAD_BLE
    override var state: TransportState = TransportState.Idle
        private set
    override var listener: PulseTransportListener? = null

    /** Hiệu chuẩn ADC từng kênh để hiển thị lực (MCU giữ bản riêng, gửi bằng [sendChannelCalibration]). */
    val mapper = PadChannelMapper()

    private val main = Handler(Looper.getMainLooper())
    private val filters = Region.entries.associateWith { ForceFilter(0.4) }
    private val trackers = Region.entries.associateWith { DepthTracker() }

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var configChar: BluetoothGattCharacteristic? = null
    private var profile: PulseProfile? = null
    private var bpm = 0.0
    private val opQueue = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private var closed = true

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean =
        requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun open() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return fail("Thiết bị không hỗ trợ Bluetooth Low Energy")
        }
        if (!hasPermissions()) return fail("Thiếu quyền Bluetooth. Hãy cấp quyền rồi thử lại")
        val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) return fail("Không tìm thấy bộ điều hợp Bluetooth")
        if (!adapter.isEnabled) return fail("Bluetooth đang tắt")
        val sc = adapter.bluetoothLeScanner ?: return fail("Không khởi tạo được bộ quét BLE")

        closed = false
        setState(TransportState.Connecting)
        scanner = sc
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(PadUuids.SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            sc.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            return fail("Bị từ chối quyền quét BLE: ${e.message}")
        }
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    override fun close() {
        closed = true
        main.removeCallbacks(scanTimeout)
        stopScan()
        opQueue.clear(); opBusy = false
        gatt?.let {
            try { it.disconnect(); it.close() } catch (e: SecurityException) { Log.w(TAG, "close: ${e.message}") }
        }
        gatt = null; configChar = null
        setState(TransportState.Idle)
    }

    override fun setProfile(profile: PulseProfile, bpm: Double) {
        this.profile = profile
        this.bpm = bpm
        writeConfig(DownlinkCodec.encodeProfile(DownlinkProfile.from(profile, bpm)))
    }

    override fun startFeedback(regions: Set<Region>) {
        if (regions.isEmpty()) return
        val mask = regions.fold(0) { m, r -> m or (1 shl r.channel) }
        writeConfig(DownlinkCodec.encodeStart(mask))
    }

    override fun stopFeedback() { writeConfig(DownlinkCodec.encodeStop()) }

    /** Gửi ngưỡng ADC (light < medium < hard) của một kênh xuống MCU và dùng cho hiển thị phía app. */
    fun sendChannelCalibration(region: Region, light: Int, medium: Int, hard: Int) {
        val packet = DownlinkCodec.encodeCalibration(region.channel, light, medium, hard) // ném IllegalArgumentException nếu sai
        mapper.setCalibration(region, ChannelCalibration(light.toDouble(), medium.toDouble(), hard.toDouble()))
        writeConfig(packet)
    }

    // ---- Quét & kết nối -------------------------------------------------------------------------

    private val scanTimeout = Runnable {
        stopScan()
        if (state == TransportState.Connecting && gatt == null) fail("Không tìm thấy đệm mạch (hết ${SCAN_TIMEOUT_MS / 1000}s)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (closed || gatt != null) return@post
                stopScan()
                main.removeCallbacks(scanTimeout)
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { fail("Quét BLE thất bại (mã $errorCode)") }
        }
    }

    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) { Log.w(TAG, "stopScan: ${e.message}") }
    }

    private fun connect(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("connectGatt trả về null")
        } catch (e: SecurityException) {
            fail("Bị từ chối quyền kết nối BLE: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (closed || g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    try { g.requestMtu(64) } catch (e: SecurityException) { fail("Quyền BLE: ${e.message}") }
                } else {
                    resetForces()
                    try { g.close() } catch (e: SecurityException) { /* bỏ qua */ }
                    gatt = null; configChar = null; opQueue.clear(); opBusy = false
                    fail(if (status == BluetoothGatt.GATT_SUCCESS) "Đệm đã ngắt kết nối" else "Mất kết nối BLE (mã $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (!closed && g === gatt) try { g.discoverServices() } catch (e: SecurityException) { fail("Quyền BLE: ${e.message}") } }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (closed || g !== gatt) return@post
                val svc = g.getService(UUID.fromString(PadUuids.SERVICE))
                val force = svc?.getCharacteristic(UUID.fromString(PadUuids.FORCE_UPLINK))
                val config = svc?.getCharacteristic(UUID.fromString(PadUuids.CONFIG_DOWNLINK))
                val beat = svc?.getCharacteristic(UUID.fromString(PadUuids.BEAT_NOTIFY))
                if (status != BluetoothGatt.GATT_SUCCESS || force == null || config == null) {
                    return@post fail("Thiết bị không phải đệm PulseMaster (thiếu dịch vụ/đặc tính)")
                }
                configChar = config
                enqueueNotify(g, force)
                if (beat != null) enqueueNotify(g, beat)
                enqueue {
                    // Sẵn sàng: đẩy cấu hình đang chờ xuống MCU.
                    setState(TransportState.Ready)
                    profile?.let { setProfile(it, bpm) }
                    false
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) fail("Không bật được thông báo BLE (mã $status)")
                else opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Ghi cấu hình lỗi, mã $status")
                opDone()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(c.uuid, value)
        }

        // Android 8–12
        @Deprecated("Dùng cho API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) dispatch(c.uuid, c.value ?: return)
        }
    }

    // ---- Hàng đợi thao tác GATT (mỗi lần chỉ một thao tác) ----------------------------------------

    /** Thao tác trả true nếu đã gửi và đang chờ callback, false nếu hoàn tất ngay. */
    private fun enqueue(op: () -> Boolean) {
        opQueue.add(op)
        if (!opBusy) pump()
    }

    private fun opDone() { opBusy = false; pump() }

    private fun pump() {
        while (!opBusy) {
            val op = opQueue.poll() ?: return
            // Đặt busy TRƯỚC khi chạy: thao tác con (vd. setProfile trong bước Ready) enqueue lại mà không pump đệ quy.
            opBusy = true
            opBusy = try { op() } catch (e: SecurityException) { fail("Quyền BLE: ${e.message}"); false }
        }
    }

    private fun enqueueNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) = enqueue {
        if (!g.setCharacteristicNotification(c, true)) { fail("Không đăng ký được thông báo ${c.uuid}"); return@enqueue false }
        val cccd = c.getDescriptor(CCCD) ?: run { fail("Đặc tính ${c.uuid} thiếu CCCD"); return@enqueue false }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }
        if (!ok) fail("Ghi CCCD thất bại")
        ok
    }

    private fun writeConfig(bytes: ByteArray) {
        val g = gatt
        val c = configChar
        if (g == null || c == null) return // chưa kết nối: cấu hình được gửi lại khi Ready
        enqueue {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = bytes; g.writeCharacteristic(c) }
            }
            if (!ok) Log.w(TAG, "writeCharacteristic bị từ chối")
            ok
        }
    }

    // ---- Dữ liệu vào -----------------------------------------------------------------------------

    private fun dispatch(uuid: UUID, value: ByteArray) {
        val now = SystemClock.uptimeMillis()
        when (uuid.toString().lowercase()) {
            PadUuids.FORCE_UPLINK -> {
                val frame = try { UplinkCodec.decode(value) } catch (e: PacketException) { Log.w(TAG, "Bỏ gói lực: ${e.message}"); return }
                main.post { onForceFrame(frame, now) }
            }
            PadUuids.BEAT_NOTIFY -> {
                val notice = try { BeatNoticeCodec.decode(value) } catch (e: PacketException) { Log.w(TAG, "Bỏ gói nhịp: ${e.message}"); return }
                main.post { onBeatNotice(notice, now) }
            }
        }
    }

    private fun onForceFrame(frame: ForceFrame, now: Long) {
        if (closed) return
        for (r in Region.entries) {
            val f = filters.getValue(r).update(mapper.force(r, frame.raw(r)))
            val depth = trackers.getValue(r).update(f)
            listener?.onForce(r, if (depth == Depth.NONE) 0.0 else f, depth, now)
        }
    }

    private fun onBeatNotice(n: BeatNotice, now: Long) {
        val p = profile ?: return
        val interval = 60_000.0 / bpm.coerceIn(BeatScheduler.MIN_BPM, BeatScheduler.MAX_BPM)
        val plan = BeatPlan(Beat(n.seq, interval, n.silent, 1.0), n.peak, WaveformSynth.synth(p, interval), null)
        listener?.onBeat(BeatEvent(now, plan, n.peak, n.silent, interval))
    }

    private fun resetForces() {
        for (r in Region.entries) {
            filters.getValue(r).reset(); trackers.getValue(r).reset()
            listener?.onForce(r, 0.0, Depth.NONE, SystemClock.uptimeMillis())
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        stopScan()
        setState(TransportState.Failed(reason))
    }

    private fun setState(s: TransportState) {
        state = s
        listener?.onStateChanged(s)
    }

    private companion object {
        const val TAG = "BleWristPad"
        const val SCAN_TIMEOUT_MS = 10_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
