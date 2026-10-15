// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/lp2p_nonces.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.wifi_ap.lp2p

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A chosen nonce index with both forms of its nonce.
 *
 * @property index the 16-bit table index.
 * @property plainNonce the 16-byte plaintext nonce (`WellKnownStr`, revision, index) used for key derivation.
 * @property encryptedNonce the matching 16-byte pre-encrypted nonce, placed in the beacon.
 */
class NoncePair(val index: Int, val plainNonce: ByteArray, val encryptedNonce: ByteArray)

/**
 * Table of 65536 pre-encrypted LP2P nonces plus the logic that picks one.
 *
 * Business logic: the beacon carries a 128-bit nonce encrypted under a key that lives inside the
 * kart, which this project must not embed. OpenKart's workaround is a table of pre-encrypted nonces
 * (AES-128-CTR keystream, 65536 x 16 bytes). Each nonce has a 16-bit id; its plaintext form is
 * `"WellKnownStr" || revision(2, BE) || id(2, BE)`, and its encrypted form is the 16 bytes at
 * `id * 16` of the table. (OpenKart reads the table through a base64 block calculation; that is
 * exactly a flat `id * 16` slice of the decoded bytes, which the generator proves for every id.)
 *
 * Index selection ([getNoncePair]):
 * - persistent network (`pureRandom = false`): the top 12 bits come from time in 30-minute periods
 *   (the id repeats only after about 85 days) and the low 4 bits from a counter that starts random
 *   and then increments, so a reused nonce is unlikely;
 * - temporary network (`pureRandom = true`): a uniformly random 16-bit id.
 *
 * Time and randomness are injected so tests can replay OpenKart's recorded behaviour. The counter
 * state mirrors OpenKart's process-wide `_seq`: share one [NonceTable] for the lifetime of the app
 * to keep the sequence going. Not thread-safe; call from a single thread or coroutine.
 *
 * Ported from OpenKart `lp2p_nonces.py` (`get_encrypted_nonce`, `get_plaintext_nonce`,
 * `get_nonce_pair`, `get_seq`).
 *
 * @param table the decoded table, exactly 65536 * 16 bytes; defaults to the bundled resource.
 * @param nowEpochSeconds current Unix time in whole seconds.
 * @param randomInt returns a uniform integer in `[0, bound)`.
 */
class NonceTable(
    private val table: ByteArray = loadLp2pNonceTable(),
    private val nowEpochSeconds: () -> Long = ::currentEpochSeconds,
    private val randomInt: (Int) -> Int = { bound -> Random.nextInt(bound) },
) {

    /** Low 4-bit sequence counter; null until the first time-based draw. */
    private var sequence: Int? = null

    init {
        require(table.size == TABLE_BYTES) { "nonce table must be $TABLE_BYTES bytes, was ${table.size}" }
    }

    /** Returns the 16-byte pre-encrypted nonce for [index] (0..0xFFFF). */
    fun getEncryptedNonce(index: Int): ByteArray {
        requireValidIndex(index)
        return table.copyOfRange(index * NONCE_BYTES, (index + 1) * NONCE_BYTES)
    }

    /** Returns the 16-byte plaintext nonce for [index] (0..0xFFFF): `WellKnownStr || 0001 || index`. */
    fun getPlaintextNonce(index: Int): ByteArray {
        requireValidIndex(index)
        return WELL_KNOWN + byteArrayOf(
            (REVISION shr 8).toByte(),
            REVISION.toByte(),
            (index shr 8).toByte(),
            index.toByte(),
        )
    }

    /**
     * Picks a nonce index and returns both nonce forms.
     *
     * @param pureRandom true for a uniformly random index (temporary networks); false for the
     * time-plus-sequence index used by the persistent network.
     */
    fun getNoncePair(pureRandom: Boolean = false): NoncePair {
        val index = if (pureRandom) randomInt(TABLE_ENTRIES) else getSeq()
        return NoncePair(index, getPlaintextNonce(index), getEncryptedNonce(index))
    }

    /**
     * Returns the next time-based nonce index: `((now / 1800s) << 4 | seq) & 0xFFFF`, where `seq`
     * is a 4-bit counter that starts at a random value and increments (wrapping) on every call.
     */
    fun getSeq(): Int {
        val next = ((sequence ?: randomInt(SEQ_VALUES)) + 1) and SEQ_MASK
        sequence = next
        val period = nowEpochSeconds() / SEQ_PERIOD_SECONDS
        return (((period shl SEQ_BITS) or next.toLong()) and INDEX_MASK).toInt()
    }

    private fun requireValidIndex(index: Int) {
        require(index in 0 until TABLE_ENTRIES) { "nonce index out of range: $index" }
    }

    private companion object {
        const val NONCE_BYTES = 16
        const val TABLE_ENTRIES = 0x10000
        const val TABLE_BYTES = TABLE_ENTRIES * NONCE_BYTES

        const val SEQ_BITS = 4
        const val SEQ_VALUES = 1 shl SEQ_BITS
        const val SEQ_MASK = SEQ_VALUES - 1
        const val INDEX_MASK = 0xFFFFL
        const val SEQ_PERIOD_SECONDS = 30L * 60L

        const val REVISION = 0x0001

        /** `b"WellKnownStr"`: the 12-byte fixed prefix of every plaintext nonce. */
        val WELL_KNOWN: ByteArray = "WellKnownStr".encodeToByteArray()
    }
}

/** Default clock for [NonceTable]: the current Unix time in whole seconds. */
@OptIn(ExperimentalTime::class)
private fun currentEpochSeconds(): Long = Clock.System.now().epochSeconds
