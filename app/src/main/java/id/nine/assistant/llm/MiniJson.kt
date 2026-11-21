package id.nine.assistant.llm

/**
 * Pembaca/penulis JSON kecil tanpa ketergantungan Android, supaya parser balasan AI bisa diuji di JVM biasa.
 * Hasil: Map<String, Any?>, List<Any?>, String, Long/Double, Boolean, atau null (untuk JSON null).
 */
object MiniJson {
    class ParseException(msg: String) : Exception(msg)

    private const val MAX_DEPTH = 64

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw ParseException("sisa teks setelah JSON")
        return v
    }

    /** Null bila bukan JSON valid. (Perhatikan: "null" yang valid juga menghasilkan null.) */
    fun parseOrNull(s: String): Any? = try {
        parse(s)
    } catch (e: ParseException) {
        null
    }

    /** Telusuri nilai dengan kunci (String) dan indeks (Int); null bila tak ada atau tipe tak cocok. */
    fun get(v: Any?, vararg path: Any): Any? {
        var cur: Any? = v
        for (k in path) {
            cur = when {
                k is String && cur is Map<*, *> -> cur[k]
                k is Int && cur is List<*> -> cur.getOrNull(k)
                else -> return null
            }
        }
        return cur
    }

    fun str(v: Any?, vararg path: Any): String? = get(v, *path) as? String

    fun num(v: Any?, vararg path: Any): Long? = when (val x = get(v, *path)) {
        is Long -> x
        is Double -> if (x == Math.floor(x) && !x.isInfinite()) x.toLong() else null
        is String -> x.trim().toLongOrNull()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?, vararg path: Any): Map<String, Any?>? = get(v, *path) as? Map<String, Any?>

    fun arr(v: Any?, vararg path: Any): List<Any?>? = get(v, *path) as? List<Any?>

    // ------------------------------------------------------------------ menulis

    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, v)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, x)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' || ch == '\u2028' || ch == '\u2029' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ------------------------------------------------------------------ membaca

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '\uFEFF')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("terlalu dalam")
            if (atEnd()) throw ParseException("JSON terpotong")
            return when (val c = s[i]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw ParseException("karakter tak terduga '$c' di $i")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("literal tak valid di $i")
            i += word.length
            return v
        }

        fun objectValue(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') throw ParseException("kunci objek harus berupa string di $i")
                val k = string()
                skipWs()
                if (atEnd() || s[i] != ':') throw ParseException("':' hilang di $i")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (atEnd()) throw ParseException("objek terpotong")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw ParseException("',' atau '}' hilang di $i")
                }
            }
        }

        fun arrayValue(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (atEnd()) throw ParseException("larik terpotong")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw ParseException("',' atau ']' hilang di $i")
                }
            }
        }

        fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("string terpotong")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("escape terpotong")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("\\u terpotong")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: throw ParseException("\\u tak valid: $hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw ParseException("escape tak dikenal \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            val digitsStart = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == digitsStart) throw ParseException("angka tak valid di $start")
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) throw ParseException("pecahan tak valid di $start")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) throw ParseException("eksponen tak valid di $start")
            }
            val tok = s.substring(start, i)
            if (integral) tok.toLongOrNull()?.let { return it }
            return tok.toDoubleOrNull() ?: throw ParseException("angka tak valid: $tok")
        }
    }
}
