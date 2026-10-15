package com.mklhc.core.kart_protocol.rcd

/**
 * Typed view of `rcd_vectors.json`, the golden vectors produced by `tools/gen_rcd_vectors.py`
 * from the real OpenKart implementation (openkartd/rcd.py).
 *
 * The handshake transcript is fully deterministic: the generator pins the server's os.urandom
 * challenge, so the Kotlin handshake can be driven with the recorded challenge and asserted byte for
 * byte, including the running-SHA-256 finalize digest.
 */
internal class RcdVectors private constructor(root: Map<String, Any?>) {

    class MsgEncode(
        val name: String,
        val service: Int,
        val command: Int,
        val status: Int,
        val isResponse: Boolean,
        val data: ByteArray,
        val encoded: ByteArray,
    )

    class MsgSize(val name: String, val buffer: ByteArray, val size: Int)

    class MsgDecode(
        val name: String,
        val buffer: ByteArray,
        val service: Int,
        val command: Int,
        val status: Int,
        val isResponse: Boolean,
        val data: ByteArray,
    )

    class PairingKeys(
        val name: String,
        val masterKey: ByteArray,
        val deviceIdent: ByteArray,
        val deviceName: String,
        val pairingId: ByteArray,
        val secretKey: ByteArray,
    )

    class HandshakeStep(
        val command: Int,
        val request: ByteArray,
        val response: ByteArray,
        val finalizeCheckInput: ByteArray?,
        val finalizeDigest: ByteArray?,
    )

    class Handshake(
        val serverName: String,
        val serverIdent: ByteArray,
        val masterKey: ByteArray,
        val versions: List<Int>,
        val deviceName: String,
        val deviceIdent: ByteArray,
        val pairingId: ByteArray,
        val secretKey: ByteArray,
        val selectedVersion: Int,
        val beginChallenge: ByteArray,
        val steps: List<HandshakeStep>,
    )

    val errorCodes: Map<String, Int> = root.obj("constants").obj("error_codes")
        .mapValues { (_, v) -> (v as String).toInt(16) }

    val maxDataSize: Int = (root.obj("constants")["max_data_size"] as Double).toInt()
    val headerSize: Int = (root.obj("constants")["header_size"] as Double).toInt()

    val msgEncode: List<MsgEncode> = root.list("msg_encode").map { v ->
        val o = v.obj()
        MsgEncode(
            name = o.string("name"),
            service = o.int("service"),
            command = o.int("command"),
            status = o.int("status"),
            isResponse = o["is_response"] as Boolean,
            data = o.bytes("data"),
            encoded = o.bytes("encoded"),
        )
    }

    val msgSize: List<MsgSize> = root.list("msg_size").map { v ->
        val o = v.obj()
        MsgSize(o.string("name"), o.bytes("buffer"), o.int("size"))
    }

    val msgDecode: List<MsgDecode> = root.list("msg_decode").map { v ->
        val o = v.obj()
        MsgDecode(
            name = o.string("name"),
            buffer = o.bytes("buffer"),
            service = o.int("service"),
            command = o.int("command"),
            status = o.int("status"),
            isResponse = o["is_response"] as Boolean,
            data = o.bytes("data"),
        )
    }

    val pairingKeys: List<PairingKeys> = root.list("pairing_keys").map { v ->
        val o = v.obj()
        PairingKeys(
            name = o.string("name"),
            masterKey = o.bytes("master_key"),
            deviceIdent = o.bytes("device_ident"),
            deviceName = o.string("device_name"),
            pairingId = o.bytes("pairing_id"),
            secretKey = o.bytes("secret_key"),
        )
    }

    val handshake: Handshake = parseHandshake(root.obj("handshake"))

    /** The pairing-path transcript (cmd 1 -> 2 -> 3 -> 4): an unknown device routed through get-secret-key. */
    val pairingHandshake: Handshake = parseHandshake(root.obj("pairing_handshake"))

    companion object {
        private const val RESOURCE_NAME = "rcd_vectors.json"

        /** Loads and parses the vectors file from the test classpath. */
        fun load(): RcdVectors = RcdVectors(MiniJson.parse(readTestResource(RESOURCE_NAME)).obj())

        private fun parseHandshake(h: Map<String, Any?>): Handshake {
            val serverInfo = h.obj("server_info")
            val device = h.obj("device")
            return Handshake(
                serverName = serverInfo.string("name"),
                serverIdent = serverInfo.bytes("ident"),
                masterKey = serverInfo.bytes("master_key"),
                versions = serverInfo.list("versions").map { (it as Double).toInt() },
                deviceName = device.string("name"),
                deviceIdent = device.bytes("ident"),
                pairingId = h.bytes("pairing_id"),
                secretKey = h.bytes("secret_key"),
                selectedVersion = h.int("selected_version"),
                beginChallenge = h.bytes("begin_challenge"),
                steps = h.list("steps").map { v ->
                    val s = v.obj()
                    HandshakeStep(
                        command = s.int("command"),
                        request = s.bytes("request"),
                        response = s.bytes("response"),
                        finalizeCheckInput = (s["finalize_check_input"] as String?)?.hexToByteArray(),
                        finalizeDigest = (s["finalize_digest"] as String?)?.hexToByteArray(),
                    )
                },
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

private fun Map<String, Any?>.string(key: String): String = this[key] as String

private fun Map<String, Any?>.int(key: String): Int = (this[key] as Double).toInt()

private fun Map<String, Any?>.bytes(key: String): ByteArray = string(key).hexToByteArray()

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
