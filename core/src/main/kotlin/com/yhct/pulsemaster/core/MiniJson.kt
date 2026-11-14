package com.yhct.pulsemaster.core

/**
 * Bộ phân tích JSON tối giản (không phụ thuộc org.json để lõi chạy được trên JVM thuần).
 * Trả về: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw PulseDataException("JSON thừa dữ liệu ở vị trí ${p.pos}")
        return v
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun fail(msg: String): Nothing = throw PulseDataException("JSON lỗi ở vị trí $pos: $msg")

        fun value(): Any? {
            if (atEnd()) fail("kết thúc đột ngột")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("ký tự lạ '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("mong đợi $word")
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("số không hợp lệ")
        }

        private fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("chuỗi chưa đóng")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("escape dở dang")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u dở dang")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("\\u không hợp lệ")
                                sb.append(code.toChar()); pos += 4
                            }
                            else -> fail("escape lạ '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs(); out.add(value()); skipWs()
                if (atEnd()) fail("mảng chưa đóng")
                when (s[pos++]) { ',' -> continue; ']' -> return out; else -> fail("mong đợi , hoặc ]") }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("mong đợi khóa chuỗi")
                val k = str(); skipWs()
                if (atEnd() || s[pos++] != ':') fail("mong đợi :")
                skipWs(); out[k] = value(); skipWs()
                if (atEnd()) fail("object chưa đóng")
                when (s[pos++]) { ',' -> continue; '}' -> return out; else -> fail("mong đợi , hoặc }") }
            }
        }
    }
}
