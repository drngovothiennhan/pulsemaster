package com.yhct.pulsemaster.core

import kotlin.test.*

class CatalogTest {
    @Test fun `loads all 28 pulses with unique ids`() {
        val c = TestData.catalog
        assertEquals(28, c.pulses.size)
        assertEquals(28, c.pulses.map { it.id }.toSet().size)
        assertEquals("1.0.0", c.schemaVersion)
    }

    @Test fun `all five rhythm types are present and carry their parameters`() {
        val c = TestData.catalog
        assertEquals(RhythmType.entries.toSet(), c.pulses.map { it.rhythm }.toSet())
        assertEquals(0.18, c.require("xuc").pauseProbability)
        assertEquals(0.20, c.require("ket").pauseProbability)
        assertEquals(4, c.require("dai").pauseEveryNBeats)
        assertEquals(RhythmType.IRREGULAR_FADE, c.require("tan").rhythm)
        assertEquals(RhythmType.SLIGHTLY_UNEVEN, c.require("sap").rhythm)
    }

    @Test fun `region emphasis for doan and dong peaks at quan`() {
        for (id in listOf("doan", "dong")) {
            val w = TestData.p(id).regionEmphasis
            assertEquals(1.0, w.quan); assertTrue(w.thon < 0.5 && w.xich < 0.5, id)
        }
    }

    @Test fun `unicode han names survive parsing`() {
        assertEquals("浮脉", TestData.p("phu").nameHan)
        assertEquals("Mạch Phù", TestData.p("phu").nameVi)
    }

    private fun base(extra: String = "", rhythm: String = "regular", amp: String = "0.5") = """
      {"schema_version":"1.0.0","pulses":[{"id":"x","name_vi":"X","name_han":"X","category":"c",
       "bpm_range":[60,80],"default_bpm":70,"depth_amplitude":{"phu":$amp,"trung":0.5,"tram":0.5},
       "sharpness":0.5,"upstroke_ms":30,"dicrotic_ratio":0.3,"rhythm_type":"$rhythm",
       "region_emphasis":{"thon":1,"quan":1,"xich":1},"clinical_feel":"f"$extra}]}"""

    @Test fun `valid minimal document parses`() { assertEquals(1, PulseCatalog.parse(base()).pulses.size) }

    @Test fun `rejects bad data with PulseDataException`() {
        fun bad(json: String) = assertFailsWith<PulseDataException> { PulseCatalog.parse(json) }
        bad(base(amp = "1.5"))
        bad(base(amp = "-0.1"))
        bad(base(rhythm = "weird"))
        bad(base(rhythm = "intermittent_regular"))                       // thiếu pause_every_n_beats
        bad(base(rhythm = "intermittent_irregular"))                     // thiếu pause_probability
        bad(base(extra = ",\"pause_every_n_beats\":0", rhythm = "intermittent_regular"))
        bad(base().replace("1.0.0", "2.0.0"))
        bad(base().replace("\"default_bpm\":70", "\"default_bpm\":90"))  // ngoài bpm_range
        bad(base().replace("\"bpm_range\":[60,80]", "\"bpm_range\":[80,60]"))
        bad(base().replace("\"upstroke_ms\":30", "\"upstroke_ms\":1"))
        bad("[]")
        bad("{")
        bad("""{"schema_version":"1.0.0","pulses":[]}""")
    }

    @Test fun `rejects duplicate ids`() {
        val one = base().substringAfter("\"pulses\":[").substringBeforeLast("]}")
        assertFailsWith<PulseDataException> {
            PulseCatalog.parse("""{"schema_version":"1.0.0","pulses":[$one,$one]}""")
        }
    }
}
