package com.yhct.pulsemaster.core

/** UUID GATT của đệm cổ tay (xem docs/BLE_PROTOCOL.md). */
object PadUuids {
    const val SERVICE = "7d0a0001-6b1f-4c3e-9a52-5c0de1f0a001"
    const val FORCE_UPLINK = "7d0a0002-6b1f-4c3e-9a52-5c0de1f0a001"
    const val CONFIG_DOWNLINK = "7d0a0003-6b1f-4c3e-9a52-5c0de1f0a001"
    const val BEAT_NOTIFY = "7d0a0004-6b1f-4c3e-9a52-5c0de1f0a001"
}

class PacketException(message: String) : Exception(message)

object Crc8 {
    /** CRC-8/SMBUS (poly 0x07, init 0). check("123456789") = 0xF4. */
    fun compute(data: ByteArray, len: Int = data.size): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }
}

/** Lực ADC 12-bit thô của 3 kênh. */
data class ForceFrame(val thon: Int, val quan: Int, val xich: Int) {
    fun raw(r: Region) = when (r) { Region.THON -> thon; Region.QUAN -> quan; Region.XICH -> xich }
}

/** Uplink ESP32-S3 → app: 6 byte = 3 × uint16 little-endian (Thốn, Quan, Xích), 12-bit (0..4095), 100 Hz. */
object UplinkCodec {
    const val SIZE = 6
    const val ADC_MAX = 4095

    fun decode(b: ByteArray): ForceFrame {
        if (b.size != SIZE) throw PacketException("Gói lực phải dài $SIZE byte, nhận ${b.size}")
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        val v = intArrayOf(u16(0), u16(2), u16(4))
        if (v.any { it > ADC_MAX }) throw PacketException("Giá trị ADC vượt 12-bit: ${v.joinToString()}")
        return ForceFrame(v[0], v[1], v[2])
    }

    fun encode(f: ForceFrame): ByteArray {
        require(f.thon in 0..ADC_MAX && f.quan in 0..ADC_MAX && f.xich in 0..ADC_MAX) { "ADC ngoài 12-bit" }
        val out = ByteArray(SIZE)
        intArrayOf(f.thon, f.quan, f.xich).forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte(); out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }
}

/** Cấu hình mạch gửi xuống MCU (đã lượng tử hóa về byte). */
data class DownlinkProfile(
    val bpmTenths: Int,
    val phu: Int, val trung: Int, val tram: Int,
    val sharpness: Int,
    val upstrokeMs: Int,
    val dicrotic: Int,
    val rhythm: RhythmType,
    val rhythmParam: Int,
    val thon: Int, val quan: Int, val xich: Int
) {
    companion object {
        private fun q(x: Double) = Math.round(x.coerceIn(0.0, 1.0) * 255.0).toInt()

        fun from(p: PulseProfile, bpm: Double = p.defaultBpm): DownlinkProfile {
            val b = bpm.coerceIn(BeatScheduler.MIN_BPM, BeatScheduler.MAX_BPM)
            val param = when (p.rhythm) {
                RhythmType.INTERMITTENT_IRREGULAR -> q(p.pauseProbability)
                RhythmType.INTERMITTENT_REGULAR -> p.pauseEveryNBeats
                else -> 0
            }
            return DownlinkProfile(
                bpmTenths = Math.round(b * 10).toInt(),
                phu = q(p.depthAmplitude.phu), trung = q(p.depthAmplitude.trung), tram = q(p.depthAmplitude.tram),
                sharpness = q(p.sharpness),
                upstrokeMs = Math.round(p.upstrokeMs).toInt().coerceIn(0, 255),
                dicrotic = q(p.dicroticRatio),
                rhythm = p.rhythm, rhythmParam = param,
                thon = q(p.regionEmphasis.thon), quan = q(p.regionEmphasis.quan), xich = q(p.regionEmphasis.xich)
            )
        }
    }
}

/** Downlink app → ESP32-S3. Khung: [ver][cmd][payload…][crc8 của mọi byte trước]. Tối đa 20 byte (MTU mặc định 23). */
object DownlinkCodec {
    const val VERSION = 1
    const val CMD_SET_PROFILE = 0x01
    const val CMD_STOP = 0x02
    const val CMD_SET_CALIBRATION = 0x03
    const val CMD_START = 0x04
    const val PROFILE_SIZE = 16

    private fun finish(body: ByteArray): ByteArray {
        val out = body.copyOf(body.size + 1)
        out[body.size] = Crc8.compute(body).toByte()
        return out
    }

    fun encodeProfile(p: DownlinkProfile): ByteArray {
        require(p.bpmTenths in 350..1800) { "bpmTenths ngoài 35.0..180.0" }
        listOf(p.phu, p.trung, p.tram, p.sharpness, p.upstrokeMs, p.dicrotic, p.rhythmParam, p.thon, p.quan, p.xich)
            .forEach { require(it in 0..255) { "Trường vượt byte: $it" } }
        val b = ByteArray(PROFILE_SIZE - 1)
        b[0] = VERSION.toByte(); b[1] = CMD_SET_PROFILE.toByte()
        b[2] = (p.bpmTenths and 0xFF).toByte(); b[3] = (p.bpmTenths shr 8).toByte()
        b[4] = p.phu.toByte(); b[5] = p.trung.toByte(); b[6] = p.tram.toByte()
        b[7] = p.sharpness.toByte(); b[8] = p.upstrokeMs.toByte(); b[9] = p.dicrotic.toByte()
        b[10] = p.rhythm.wireCode.toByte(); b[11] = p.rhythmParam.toByte()
        b[12] = p.thon.toByte(); b[13] = p.quan.toByte(); b[14] = p.xich.toByte()
        return finish(b)
    }

    fun decodeProfile(raw: ByteArray): DownlinkProfile {
        checkFrame(raw, CMD_SET_PROFILE, PROFILE_SIZE)
        fun u8(i: Int) = raw[i].toInt() and 0xFF
        val rhythm = RhythmType.fromWire(u8(10)) ?: throw PacketException("rhythm_type lạ: ${u8(10)}")
        return DownlinkProfile(
            bpmTenths = u8(2) or (u8(3) shl 8),
            phu = u8(4), trung = u8(5), tram = u8(6), sharpness = u8(7), upstrokeMs = u8(8), dicrotic = u8(9),
            rhythm = rhythm, rhythmParam = u8(11), thon = u8(12), quan = u8(13), xich = u8(14)
        )
    }

    fun encodeStop(): ByteArray = finish(byteArrayOf(VERSION.toByte(), CMD_STOP.toByte()))

    /** [regionMask]: bit0 Thốn, bit1 Quan, bit2 Xích. */
    fun encodeStart(regionMask: Int): ByteArray {
        require(regionMask in 1..7) { "regionMask phải trong 1..7" }
        return finish(byteArrayOf(VERSION.toByte(), CMD_START.toByte(), regionMask.toByte()))
    }

    /** Hiệu chuẩn ADC từng kênh để MCU tự tính lực cục bộ (độ trễ < 5 ms). */
    fun encodeCalibration(channel: Int, light: Int, medium: Int, hard: Int): ByteArray {
        require(channel in 0..2) { "channel 0..2" }
        require(light in 0..UplinkCodec.ADC_MAX && medium in 0..UplinkCodec.ADC_MAX && hard in 0..UplinkCodec.ADC_MAX) { "ADC ngoài 12-bit" }
        require(light < medium && medium < hard) { "Cần light < medium < hard" }
        val b = ByteArray(9)
        b[0] = VERSION.toByte(); b[1] = CMD_SET_CALIBRATION.toByte(); b[2] = channel.toByte()
        intArrayOf(light, medium, hard).forEachIndexed { i, v ->
            b[3 + i * 2] = (v and 0xFF).toByte(); b[4 + i * 2] = (v shr 8).toByte()
        }
        return finish(b)
    }

    private fun checkFrame(raw: ByteArray, cmd: Int, size: Int) {
        if (raw.size != size) throw PacketException("Khung dài ${raw.size}, cần $size")
        if ((raw[0].toInt() and 0xFF) != VERSION) throw PacketException("Phiên bản giao thức ${raw[0]} không hỗ trợ")
        if ((raw[1].toInt() and 0xFF) != cmd) throw PacketException("Sai lệnh: ${raw[1]}")
        if (Crc8.compute(raw, size - 1) != (raw[size - 1].toInt() and 0xFF)) throw PacketException("Sai CRC")
    }
}

/** Thông báo nhịp từ MCU (để đồ thị đồng bộ): [seq][flags bit0=bỏ nhịp][peak 0..255]. */
data class BeatNotice(val seq: Int, val silent: Boolean, val peak: Double)

object BeatNoticeCodec {
    const val SIZE = 3
    fun decode(b: ByteArray): BeatNotice {
        if (b.size != SIZE) throw PacketException("Gói nhịp phải dài $SIZE byte, nhận ${b.size}")
        val flags = b[1].toInt() and 0xFF
        return BeatNotice(b[0].toInt() and 0xFF, flags and 1 != 0, (b[2].toInt() and 0xFF) / 255.0)
    }
    fun encode(n: BeatNotice): ByteArray =
        byteArrayOf(n.seq.toByte(), (if (n.silent) 1 else 0).toByte(), Math.round(n.peak.coerceIn(0.0, 1.0) * 255).toInt().toByte())
}

/** Chuyển ADC thô thành lực chuẩn hóa bằng hiệu chuẩn từng kênh (phía app, khi cần hiển thị). */
class PadChannelMapper(private val cal: Array<ChannelCalibration?> = arrayOfNulls(3)) {
    fun setCalibration(r: Region, c: ChannelCalibration) { cal[r.channel] = c }
    fun calibration(r: Region) = cal[r.channel]
    /** Nếu chưa hiệu chuẩn, dùng tỉ lệ ADC/4095 làm lực thô. */
    fun force(r: Region, adc: Int): Double =
        cal[r.channel]?.normalize(adc.toDouble()) ?: (adc.toDouble() / UplinkCodec.ADC_MAX).coerceIn(0.0, 1.0)
}

enum class TransportKind { PHONE_INTERNAL_HAPTIC, EXTERNAL_WRIST_PAD_BLE }

sealed class TransportState {
    object Idle : TransportState()
    object Connecting : TransportState()
    object Ready : TransportState()
    data class Failed(val reason: String) : TransportState()
}

/** Sự kiện một nhịp để UI vẽ đồ thị đồng bộ. [startMs] theo đồng hồ đơn điệu của nền tảng. */
data class BeatEvent(val startMs: Long, val plan: BeatPlan?, val peak: Double, val silent: Boolean, val intervalMs: Double)

interface PulseTransportListener {
    fun onStateChanged(state: TransportState)
    /** Lực chuẩn hóa 0..1 của một bộ; 0.0 khi nhấc ngón. */
    fun onForce(region: Region, force: Double, depth: Depth, tMs: Long)
    fun onBeat(event: BeatEvent)
}

/**
 * Hợp đồng chung giữa [TransportKind.PHONE_INTERNAL_HAPTIC] và [TransportKind.EXTERNAL_WRIST_PAD_BLE]:
 * phần còn lại của app chỉ nói chuyện với giao diện này.
 */
interface PulseHardwareTransport {
    val kind: TransportKind
    val state: TransportState
    var listener: PulseTransportListener?
    fun open()
    fun close()
    fun setProfile(profile: PulseProfile, bpm: Double = profile.defaultBpm)
    /** Bắt đầu phát cho các bộ có ngón tay (điện thoại: theo cảm ứng; đệm: gửi START với mặt nạ bộ). */
    fun startFeedback(regions: Set<Region>)
    fun stopFeedback()
}
