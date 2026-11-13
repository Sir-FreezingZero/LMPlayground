package com.druk.agent.json

/**
 * Minimal JSON codec over plain Kotlin values.
 *
 * The agent core must run on a desktop JVM with no third-party artifacts
 * (org.json is only provided by the Android framework), so persistence uses
 * this instead. Values map as: object → Map<String, Any?>, array → List<Any?>,
 * string → String, number → Long when integral else Double, true/false →
 * Boolean, null → null.
 */
object Json {

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Trailing data at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Not a JSON object")

    fun quote(s: String): String = StringBuilder().also { writeString(it, s) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(fmt(v)) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(fmt(v.toDouble())) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            is Enum<*> -> writeString(sb, v.name)
            else -> writeString(sb, v.toString())
        }
    }

    private fun fmt(d: Double): String =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() + ".0" else d.toString()

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            if (atEnd()) throw JsonException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun readObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected key at $pos")
                val k = readString()
                skipWs()
                expect(':')
                skipWs()
                m[k] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw JsonException("Expected , or } at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw JsonException("Expected , or ] at $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDoubleOrNull() ?: throw JsonException("Bad number $t")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonException("Bad number $t")
            }
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]
        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("Expected '$c' at $pos")
            pos++
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

// ---- typed accessors for decoding persisted maps ----

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    list(key).mapNotNull { it as? Map<String, Any?> }

fun Map<String, Any?>.str(key: String, default: String = ""): String = this[key] as? String ?: default
fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as? String
fun Map<String, Any?>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
fun Map<String, Any?>.long(key: String, default: Long = 0): Long = (this[key] as? Number)?.toLong() ?: default
fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.double(key: String, default: Double = 0.0): Double = (this[key] as? Number)?.toDouble() ?: default
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = this[key] as? Boolean ?: default
fun Map<String, Any?>.strings(key: String): List<String> = list(key).mapNotNull { it as? String }
fun Map<String, Any?>.doubleMap(key: String): Map<String, Double> =
    obj(key)?.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }?.toMap() ?: emptyMap()

inline fun <reified E : Enum<E>> Map<String, Any?>.enum(key: String, default: E): E =
    (this[key] as? String)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
