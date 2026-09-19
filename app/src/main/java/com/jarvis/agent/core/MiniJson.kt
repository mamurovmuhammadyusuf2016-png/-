package com.jarvis.agent.core

import kotlin.math.floor

/**
 * A tiny dependency-free JSON reader/writer.
 *
 * Android ships `org.json`, but that class is a throwing stub on the JVM unit-test
 * classpath. Keeping our own parser means the exact same code runs on the device and
 * in the tests, which is what the whole `core` package is about.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        return v
    }

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: Exception) {
        null
    }

    /**
     * Models like to wrap JSON in prose or ```json fences. Pull out the first balanced
     * object so we can still read a plan out of a chatty answer.
     */
    fun extractFirstObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?> =
        (value as? Map<String, Any?>) ?: emptyMap()

    fun asArray(value: Any?): List<Any?> =
        (value as? List<Any?>) ?: emptyList()

    fun str(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Double -> if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    fun int(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: fallback
        else -> fallback
    }

    fun long(value: Any?, fallback: Long): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: fallback
        else -> fallback
    }

    fun bool(value: Any?, fallback: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toDouble() != 0.0
        else -> fallback
    }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (!d.isNaN() && !d.isInfinite() && d == floor(d) && kotlin.math.abs(d) < 1e15) {
                    sb.append(d.toLong().toString())
                } else {
                    sb.append(d.toString())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.toList(), sb)
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Bad literal at $i")
            i += word.length
        }

        fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // '{'
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                out[key] = readValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // '['
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        fun readString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("Expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 < s.length) {
                                    val hex = s.substring(i + 1, i + 5)
                                    val code = hex.toIntOrNull(16)
                                        ?: throw IllegalArgumentException("Bad \\u escape at $i")
                                    sb.append(code.toChar())
                                    i += 4
                                } else {
                                    throw IllegalArgumentException("Truncated \\u escape at $i")
                                }
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        fun readNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '-' || s[i] == '+')
            ) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '$raw' at $start")
        }
    }
}
