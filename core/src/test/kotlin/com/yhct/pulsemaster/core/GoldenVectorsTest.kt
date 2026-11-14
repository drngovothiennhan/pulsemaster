package com.yhct.pulsemaster.core

import java.io.File
import java.util.Locale
import kotlin.test.*

/**
 * Sinh/kiểm `testvectors/golden.json` – vector chuẩn chống hồi quy cho lõi và giao thức BLE (firmware ESP32 có thể dùng để đối chiếu gói tin).
 * Cập nhật có chủ đích:  gradle test -PupdateGolden
 */
class GoldenVectorsTest {
    private fun n(d: Double) = String.format(Locale.ROOT, "%.6f", d)

    private fun build(): String {
        val sb = StringBuilder("{\n")
        // 1. biên độ nội suy
        sb.append("\"amplitude\":[\n")
        val ids = listOf("phu", "tram", "khau", "phuc", "hoat", "tan")
        val rows = ArrayList<String>()
        for (id in ids) for (m in InterpolationMode.entries) {
            val d = TestData.p(id).depthAmplitude
            val vals = (0..20).joinToString(",") { n(DepthInterpolator.amplitudeAt(d, it / 20.0, m)) }
            rows.add("{\"id\":\"$id\",\"mode\":\"${m.name}\",\"values\":[$vals]}")
        }
        sb.append(rows.joinToString(",\n")).append("\n],\n")
        // 2. hiệu chuẩn
        val cal = ChannelCalibration(0.04, 0.09, 0.15)
        val raws = listOf(-1.0, 0.0, 0.02, 0.04, 0.065, 0.09, 0.12, 0.15, 0.2, 1.0)
        sb.append("\"calibration\":{\"light\":0.04,\"medium\":0.09,\"hard\":0.15,\"raws\":[${raws.joinToString(",") { n(it) }}],")
        sb.append("\"normalized\":[${raws.joinToString(",") { n(cal.normalize(it)) }}]},\n")
        // 3. sóng
        val hoat = TestData.p("hoat")
        val w = WaveformSynth.synth(hoat, 750.0)
        val steps = w.toVibrationSteps(0.8, 10)
        sb.append("\"waveform\":{\"id\":\"hoat\",\"intervalMs\":750.000000,\"peak\":0.800000,")
        sb.append("\"points\":[${w.points.joinToString(",") { "[${n(it.tMs)},${n(it.level)}]" }}],")
        sb.append("\"androidAmplitudes\":[${steps.amplitudes.joinToString(",")}]},\n")
        // 4. nhịp (nguồn ngẫu nhiên có kịch bản)
        val script = doubleArrayOf(0.5, 0.1, 0.05, 0.9, 0.1, 0.3, 0.17, 0.19, 0.0, 0.999)
        sb.append("\"rhythm\":{\"uniform\":[${script.joinToString(",") { n(it) }}],\"cases\":[\n")
        val cases = ArrayList<String>()
        for ((id, force) in listOf("xuc" to 0.5, "dai" to 0.5, "tan" to 1.0, "sap" to 0.75, "phu" to 0.5)) {
            val p = TestData.p(id)
            val s = BeatScheduler(p, uniform = ScriptedUniform(script))
            val beats = List(10) { s.next(force) }
            cases.add(
                "{\"id\":\"$id\",\"force\":${n(force)},\"beats\":[" +
                    beats.joinToString(",") { "{\"silent\":${it.silent},\"interval\":${n(it.intervalMs)},\"scale\":${n(it.amplitudeScale)}}" } + "]}"
            )
        }
        sb.append(cases.joinToString(",\n")).append("\n]},\n")
        // 5. BLE
        sb.append("\"ble\":{\"crc8_check\":\"${"%02x".format(Crc8.compute("123456789".toByteArray()))}\",")
        sb.append("\"uplink\":[{\"hex\":\"3402ff0f0000\",\"thon\":564,\"quan\":4095,\"xich\":0}],")
        sb.append("\"calibration\":{\"channel\":2,\"light\":300,\"medium\":1200,\"hard\":3000,\"hex\":\"${hex(DownlinkCodec.encodeCalibration(2, 300, 1200, 3000))}\"},")
        sb.append("\"stop\":\"${hex(DownlinkCodec.encodeStop())}\",\"start\":{\"mask\":5,\"hex\":\"${hex(DownlinkCodec.encodeStart(5))}\"},")
        sb.append("\"profiles\":[${TestData.catalog.pulses.joinToString(",") { p -> "{\"id\":\"${p.id}\",\"hex\":\"${hex(DownlinkCodec.encodeProfile(DownlinkProfile.from(p)))}\"}" }}]},\n")
        // 6. playback
        val pb = PulsePlayback(TestData.p("doan"), uniform = ScriptedUniform(script))
        val peaks = Region.entries.map { r -> n(PulsePlayback(TestData.p("doan"), uniform = ScriptedUniform(script)).planBeat(mapOf(r to 0.525)).peak) }
        sb.append("\"playback\":{\"id\":\"doan\",\"force\":0.525000,\"peaks\":{\"thon\":${peaks[0]},\"quan\":${peaks[1]},\"xich\":${peaks[2]}}}\n")
        check(pb.bpm > 0)
        sb.append("}\n")
        return sb.toString()
    }

    @Test fun `golden vectors are up to date`() {
        val text = build()
        MiniJson.parse(text) // phải là JSON hợp lệ
        val f = File("../testvectors/golden.json")
        if (System.getProperty("updateGolden").orEmpty().isNotEmpty() || !f.exists()) {
            f.parentFile.mkdirs(); f.writeText(text)
        } else {
            assertEquals(f.readText(), text, "golden.json lệch mã nguồn. Nếu thay đổi là chủ ý: gradle test -PupdateGolden.")
        }
    }
}
