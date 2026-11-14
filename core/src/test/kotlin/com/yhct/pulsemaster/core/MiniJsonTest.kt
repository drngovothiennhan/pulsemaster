package com.yhct.pulsemaster.core

import kotlin.test.*

class MiniJsonTest {
    @Test fun `parses nested values`() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"x\ny\u00e9\"q"}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), v["a"])
        assertEquals("x\nyé\"q", (v["b"] as Map<*, *>)["c"])
    }
    @Test fun `rejects malformed`() {
        for (s in listOf("", "{", "[1,", "{\"a\"}", "{\"a\":1,}", "tru", "\"abc", "1 2", "{\"a\":\"\\q\"}"))
            assertFailsWith<PulseDataException>(s) { MiniJson.parse(s) }
    }
    @Test fun `quote round trips`() {
        val s = "a\"b\\c\nd\u0001é"
        assertEquals(s, MiniJson.parse(MiniJson.quote(s)))
    }
}
