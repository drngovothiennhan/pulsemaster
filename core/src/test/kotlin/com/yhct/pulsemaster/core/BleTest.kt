package com.yhct.pulsemaster.core

import kotlin.test.*

class BleTest {
    @Test fun `crc8 matches the standard check value`() {
        assertEquals(0xF4, Crc8.compute("123456789".toByteArray()))
        assertEquals(0, Crc8.compute(ByteArray(0)))
    }

    @Test fun `uplink decodes little endian 12 bit and round trips`() {
        val f = UplinkCodec.decode(byteArrayOf(0x34, 0x02, 0xFF.toByte(), 0x0F, 0x00, 0x00))
        assertEquals(ForceFrame(0x0234, 4095, 0), f)
        assertEquals("3402ff0f0000", hex(UplinkCodec.encode(f)))
        for (v in listOf(0, 1, 2048, 4095)) assertEquals(ForceFrame(v, v, v), UplinkCodec.decode(UplinkCodec.encode(ForceFrame(v, v, v))))
        assertEquals(1, ForceFrame(1, 2, 3).raw(Region.THON)); assertEquals(3, ForceFrame(1, 2, 3).raw(Region.XICH))
    }

    @Test fun `uplink rejects wrong size or out of range adc`() {
        for (n in listOf(0, 5, 7, 20)) assertFailsWith<PacketException> { UplinkCodec.decode(ByteArray(n)) }
        assertFailsWith<PacketException> { UplinkCodec.decode(byteArrayOf(0, 0x10, 0, 0, 0, 0)) } // 0x1000
        assertFailsWith<IllegalArgumentException> { UplinkCodec.encode(ForceFrame(4096, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { UplinkCodec.encode(ForceFrame(-1, 0, 0)) }
    }

    @Test fun `profile packet golden for hoat and layout is 16 bytes`() {
        val pkt = DownlinkCodec.encodeProfile(DownlinkProfile.from(TestData.p("hoat")))
        assertEquals(16, pkt.size)
        // ver=1 cmd=1 bpm=800(0x0320) phu=.70→178.5→179=b3 trung=.90→229.5→230=e6 tram=.65→165.75→166=a6 sharp=.80→cc up=22→16 dic=.55→8c rhythm=0 param=0 w=ff ff ff crc
        assertEquals("01012003b3e6a6cc168c0000ffffff", hex(pkt).dropLast(2))
        assertEquals(Crc8.compute(pkt, 15), pkt[15].toInt() and 0xFF)
    }

    @Test fun `every one of the 28 pulses encodes and decodes losslessly after quantisation`() {
        for (p in TestData.catalog.pulses) {
            val d = DownlinkProfile.from(p)
            val pkt = DownlinkCodec.encodeProfile(d)
            assertTrue(pkt.size <= 20, "vừa MTU mặc định")
            assertEquals(d, DownlinkCodec.decodeProfile(pkt), p.id)
            assertEquals(p.rhythm, d.rhythm)
            assertEquals(Math.round(p.defaultBpm * 10).toInt(), d.bpmTenths)
        }
        val xuc = DownlinkProfile.from(TestData.p("xuc")); assertEquals(Math.round(0.18 * 255).toInt(), xuc.rhythmParam)
        assertEquals(4, DownlinkProfile.from(TestData.p("dai")).rhythmParam)
        assertEquals(Math.round(0.4 * 255).toInt(), DownlinkProfile.from(TestData.p("dong")).thon)
    }

    @Test fun `decode rejects corruption`() {
        val pkt = DownlinkCodec.encodeProfile(DownlinkProfile.from(TestData.p("phu")))
        val flipped = pkt.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertFailsWith<PacketException> { DownlinkCodec.decodeProfile(flipped) }
        assertFailsWith<PacketException> { DownlinkCodec.decodeProfile(pkt.copyOf(15)) }
        val wrongVer = pkt.copyOf().also { it[0] = 9; it[15] = Crc8.compute(it, 15).toByte() }
        assertFailsWith<PacketException> { DownlinkCodec.decodeProfile(wrongVer) }
        val badRhythm = pkt.copyOf().also { it[10] = 77; it[15] = Crc8.compute(it, 15).toByte() }
        assertFailsWith<PacketException> { DownlinkCodec.decodeProfile(badRhythm) }
    }

    @Test fun `encode validates ranges`() {
        val ok = DownlinkProfile.from(TestData.p("phu"))
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeProfile(ok.copy(bpmTenths = 10)) }
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeProfile(ok.copy(phu = 256)) }
        assertEquals(1800, DownlinkProfile.from(TestData.p("tat"), 999.0).bpmTenths)
    }

    @Test fun `control frames`() {
        assertEquals("0102" + "%02x".format(Crc8.compute(byteArrayOf(1, 2))), hex(DownlinkCodec.encodeStop()))
        assertEquals(4, DownlinkCodec.encodeStart(0b101).size)
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeStart(0) }
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeStart(8) }
        val cal = DownlinkCodec.encodeCalibration(2, 300, 1200, 3000)
        assertEquals(10, cal.size)
        assertEquals("010302" + "2c01" + "b004" + "b80b", hex(cal).dropLast(2))
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeCalibration(3, 1, 2, 3) }
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeCalibration(0, 5, 5, 6) }
        assertFailsWith<IllegalArgumentException> { DownlinkCodec.encodeCalibration(0, 1, 2, 5000) }
    }

    @Test fun `beat notice codec`() {
        val n = BeatNoticeCodec.decode(byteArrayOf(7, 1, 0xFF.toByte()))
        assertEquals(BeatNotice(7, true, 1.0), n)
        assertEquals("070100", hex(BeatNoticeCodec.encode(BeatNotice(7, true, 0.0))))
        assertFailsWith<PacketException> { BeatNoticeCodec.decode(ByteArray(2)) }
    }

    @Test fun `pad channel mapper uses per channel calibration or raw ratio`() {
        val m = PadChannelMapper()
        assertEquals(0.5, m.force(Region.QUAN, 2048), 1e-3)
        m.setCalibration(Region.QUAN, ChannelCalibration(400.0, 1200.0, 2400.0))
        assertEquals(0.525, m.force(Region.QUAN, 1200), 1e-9)
        assertEquals(0.5, m.force(Region.THON, 2048), 1e-3) // kênh khác không bị ảnh hưởng
    }

    @Test fun `uuids are valid and distinct`() {
        val all = listOf(PadUuids.SERVICE, PadUuids.FORCE_UPLINK, PadUuids.CONFIG_DOWNLINK, PadUuids.BEAT_NOTIFY)
        all.forEach { java.util.UUID.fromString(it) }
        assertEquals(4, all.toSet().size)
    }
}
