package com.mklhc.core.wifi_ap.lp2p

/**
 * Typed view of `lp2p_vectors.json`, the golden vectors produced by `tools/gen_lp2p_vectors.py`
 * from the real OpenKart implementation (openkartd/lp2p.py and openkartd/lp2p_nonces.py).
 *
 * Every vector is deterministic: the generator replaces `os.urandom`, `random` and the clock with
 * recorded sources, so the Kotlin tests can feed back the exact same inputs and compare outputs
 * byte for byte.
 */
internal class Lp2pVectors private constructor(root: Map<String, Any?>) {

    /** One `derive_keys(nonce, psk)` call and its two 16-byte results. */
    class DeriveKeys(val name: String, val nonce: ByteArray, val psk: ByteArray, val ka: ByteArray, val kc: ByteArray)

    /** One `encrypt_network` run with every non-deterministic input recorded. */
    class EncryptNetwork(
        val name: String,
        val psk: ByteArray,
        val temporary: Boolean,
        val timeSeconds: Long,
        val randrangeDraws: List<RandrangeDraw>,
        val plainNonce: ByteArray,
        val encryptedNonce: ByteArray,
        val urandomDraws: List<ByteArray>,
        val ies: ByteArray,
        val ccmpKey: ByteArray,
    )

    /** One recorded `random.randrange(bound)` result. */
    class RandrangeDraw(val bound: Int, val value: Int)

    /** Plaintext and encrypted nonce for one table index. */
    class NonceSample(val index: Int, val plaintextNonce: ByteArray, val encryptedNonce: ByteArray)

    /** One `get_nonce_pair` call in the sequence scenario. */
    class NoncePairCall(val pureRandom: Boolean, val index: Int, val plainNonce: ByteArray, val encryptedNonce: ByteArray)

    /** A fixed-clock, scripted-random sequence of `get_nonce_pair` calls. */
    class NoncePairScenario(val timeSeconds: Long, val randrangeDraws: List<RandrangeDraw>, val calls: List<NoncePairCall>)

    val deriveKeys: List<DeriveKeys> = root.list("derive_keys").map { v ->
        val o = v.obj()
        DeriveKeys(o.string("name"), o.bytes("nonce"), o.bytes("psk"), o.bytes("ka"), o.bytes("kc"))
    }

    val encryptNetwork: List<EncryptNetwork> = root.list("encrypt_network").map { v ->
        val o = v.obj()
        EncryptNetwork(
            name = o.string("name"),
            psk = o.bytes("psk"),
            temporary = o["temporary"] as Boolean,
            timeSeconds = (o["time"] as Double).toLong(),
            randrangeDraws = o.randrangeDraws("randrange_draws"),
            plainNonce = o.bytes("plain_nonce"),
            encryptedNonce = o.bytes("encrypted_nonce"),
            urandomDraws = o.list("urandom_draws").map { (it as String).hexToByteArray() },
            ies = o.bytes("ies"),
            ccmpKey = o.bytes("ccmp_key"),
        )
    }

    val nonceSamples: List<NonceSample> = root.list("nonce_samples").map { v ->
        val o = v.obj()
        NonceSample((o["index"] as Double).toInt(), o.bytes("plaintext_nonce"), o.bytes("encrypted_nonce"))
    }

    val noncePair: NoncePairScenario = root.obj("nonce_pair").let { o ->
        NoncePairScenario(
            timeSeconds = (o["time"] as Double).toLong(),
            randrangeDraws = o.randrangeDraws("randrange_draws"),
            calls = o.list("calls").map { v ->
                val c = v.obj()
                NoncePairCall(
                    pureRandom = c["pure_random"] as Boolean,
                    index = (c["index"] as Double).toInt(),
                    plainNonce = c.bytes("plain_nonce"),
                    encryptedNonce = c.bytes("encrypted_nonce"),
                )
            },
        )
    }

    /** Size in bytes the decoded nonce table must have. */
    val nonceTableSize: Int = (root.obj("nonce_table")["size"] as Double).toInt()

    /** Lower-case hex SHA-256 of the decoded nonce table. */
    val nonceTableSha256: String = root.obj("nonce_table").string("sha256")

    companion object {
        private const val RESOURCE_NAME = "lp2p_vectors.json"

        /** Loads and parses the vectors file from the test classpath. */
        fun load(): Lp2pVectors = Lp2pVectors(MiniJson.parse(readTestResource(RESOURCE_NAME)).obj())
    }
}

private fun Map<String, Any?>.randrangeDraws(key: String): List<Lp2pVectors.RandrangeDraw> =
    list(key).map { v ->
        val o = v.obj()
        Lp2pVectors.RandrangeDraw((o["bound"] as Double).toInt(), (o["value"] as Double).toInt())
    }

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

private fun Map<String, Any?>.string(key: String): String = this[key] as String

private fun Map<String, Any?>.bytes(key: String): ByteArray = string(key).hexToByteArray()

/**
 * Minimal JSON reader, enough for the vectors file (objects, arrays, strings without exotic
 * escapes, numbers, booleans and null). It exists so commonTest needs no serialization library.
 * Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double`.
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
