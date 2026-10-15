package com.mklhc.core.data

// Test-only helper, a copy of the MiniJson in :core:kart-protocol's commonTest (RcdVectors.kt). The
// two modules have no shared test-fixtures module, so the small parser is duplicated rather than
// pulling a serialization library into every module just to read golden vectors.

/**
 * Minimal JSON reader, enough for the vectors file (objects, arrays, strings without exotic escapes,
 * numbers, booleans and null). It exists so commonTest needs no serialization library. Objects become
 * `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double`.
 */
internal object MiniJson {

    /** Parses [text] into maps, lists, strings, doubles, booleans and null. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        check(reader.atEnd()) { "Trailing characters after JSON value at ${reader.position}" }
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("Unexpected '$c' at $position")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++ // '{'
            skipWhitespace()
            if (text[position] == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                if (text[position] == ',') {
                    position++
                } else {
                    expect('}')
                    return result
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            position++ // '['
            skipWhitespace()
            if (text[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                if (text[position] == ',') {
                    position++
                } else {
                    expect(']')
                    return result
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (text[position] != '"') {
                val c = text[position++]
                if (c == '\\') {
                    when (val escaped = text[position++]) {
                        '"', '\\', '/' -> sb.append(escaped)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> error("Unsupported escape '\\$escaped' at ${position - 1}")
                    }
                } else {
                    sb.append(c)
                }
            }
            position++ // closing quote
            return sb.toString()
        }

        private fun readNumber(): Double {
            val start = position
            while (position < text.length && (text[position].isDigit() || text[position] in "+-.eE")) position++
            return text.substring(start, position).toDouble()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            check(text.startsWith(literal, position)) { "Expected '$literal' at $position" }
            position += literal.length
            return value
        }

        private fun expect(c: Char) {
            check(text[position] == c) { "Expected '$c' at $position but found '${text[position]}'" }
            position++
        }
    }
}
