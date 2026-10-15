// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/config.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.data.identity

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.kart_protocol.rcd.RcdServerInfo
import kotlin.io.encoding.Base64

/** The prefix of the game network's SSID. Replaces the reference implementation's own brand prefix. */
const val GAME_SSID_PREFIX = "mklhc_"

/** The display name this app presents as the RCD server in the handshake (fewer than 16 octets). */
const val RCD_SERVER_NAME = "MKLHC"

private const val SSID_RANDOM_SIZE = 0xC
private const val PSK_SIZE = 0x20
private const val RCD_IDENT_SIZE = 0x10
private const val RCD_MASTER_KEY_SIZE = 0x40
private const val MAX_SSID_OCTETS = 0x20
private const val HMAC_SHA512_SIZE = 0x40

private const val LABEL_SSID = "wireless/ssid"
private const val LABEL_PSK = "wireless/psk"
private const val LABEL_RCD_IDENT = "rcd/ident"
private const val LABEL_RCD_MASTER_KEY = "rcd/master_key"

/**
 * Everything that identifies this installation to a kart, derived from one master secret.
 *
 * Business logic: the game network the kart joins and the RCD server identity the kart pairs with
 * must stay the same across app launches (a paired kart reconnects on its own) yet be unguessable.
 * Rather than store four separate secrets, the app stores one master secret and re-derives these four
 * values from it each time (see [deriveKartIdentity]).
 *
 * The arrays are copied in; callers must not mutate the ones they read. [toString] shows only the
 * SSID, which is public (it is broadcast in the clear); the pre-shared key, the RCD identity and the
 * RCD master key are secrets and never appear in a string or a log.
 *
 * @property gameSsid the game network name, [GAME_SSID_PREFIX] followed by base64 of 12 derived octets.
 * @property gamePsk the game network's 32-octet pre-shared key.
 * @property rcdIdent the 16-octet identity the RCD server presents in the handshake.
 * @property rcdMasterKey the 64-octet root key all RCD pairing credentials derive from.
 * @throws IllegalArgumentException if the SSID is empty or too long, or an array has the wrong size.
 */
class KartIdentity(
    val gameSsid: String,
    gamePsk: ByteArray,
    rcdIdent: ByteArray,
    rcdMasterKey: ByteArray,
) {
    val gamePsk: ByteArray = gamePsk.copyOf()
    val rcdIdent: ByteArray = rcdIdent.copyOf()
    val rcdMasterKey: ByteArray = rcdMasterKey.copyOf()

    init {
        require(gameSsid.isNotEmpty()) { "gameSsid must not be empty" }
        require(gameSsid.encodeToByteArray().size < MAX_SSID_OCTETS) { "gameSsid must be shorter than $MAX_SSID_OCTETS octets" }
        require(gamePsk.size == PSK_SIZE) { "gamePsk must be $PSK_SIZE octets, got ${gamePsk.size}" }
        require(rcdIdent.size == RCD_IDENT_SIZE) { "rcdIdent must be $RCD_IDENT_SIZE octets, got ${rcdIdent.size}" }
        require(rcdMasterKey.size == RCD_MASTER_KEY_SIZE) { "rcdMasterKey must be $RCD_MASTER_KEY_SIZE octets, got ${rcdMasterKey.size}" }
    }

    /**
     * Builds the RCD server description the kart-protocol layer needs: this app's [RCD_SERVER_NAME]
     * with the derived identity and master key.
     *
     * @param crypto the crypto primitives the server uses to derive per-device pairing credentials.
     */
    fun toRcdServerInfo(crypto: CryptoPrimitives): RcdServerInfo =
        RcdServerInfo(name = RCD_SERVER_NAME, ident = rcdIdent, masterKey = rcdMasterKey, crypto = crypto)

    override fun equals(other: Any?): Boolean =
        other is KartIdentity &&
            gameSsid == other.gameSsid &&
            gamePsk.contentEquals(other.gamePsk) &&
            rcdIdent.contentEquals(other.rcdIdent) &&
            rcdMasterKey.contentEquals(other.rcdMasterKey)

    override fun hashCode(): Int {
        var result = gameSsid.hashCode()
        result = HASH_PRIME * result + gamePsk.contentHashCode()
        result = HASH_PRIME * result + rcdIdent.contentHashCode()
        result = HASH_PRIME * result + rcdMasterKey.contentHashCode()
        return result
    }

    override fun toString(): String = "KartIdentity(gameSsid=$gameSsid, keys=<redacted>)"

    private companion object {
        const val HASH_PRIME = 31
    }
}

/**
 * Derives the installation's [KartIdentity] from [masterSecret].
 *
 * Business logic, ported from OpenKart's `Config.process` `derive()` helper: each value is
 * `HMAC-SHA512(key = secret, message = name)` truncated to the length that value needs, with a
 * distinct `name` per value so none can be computed from another:
 *
 * | value          | name             | octets |
 * | -------------- | ---------------- | ------ |
 * | SSID tail      | `wireless/ssid`  | 12 (base64) |
 * | game PSK       | `wireless/psk`   | 32     |
 * | RCD identity   | `rcd/ident`      | 16     |
 * | RCD master key | `rcd/master_key` | 64     |
 *
 * The SSID is [GAME_SSID_PREFIX] followed by the standard base64 (with padding, 16 characters) of the
 * 12 derived octets; the derivation is the reference's, only the literal prefix differs. A master
 * secret is either a user-chosen string (use the `String` overload, which keys the HMAC with its
 * UTF-8 bytes exactly as the reference does) or 32 random octets generated once per installation.
 *
 * The secret is never logged or echoed in an error.
 *
 * @param masterSecret the installation's master secret; must not be empty.
 * @param crypto the crypto primitives providing HMAC-SHA512.
 * @throws IllegalArgumentException if [masterSecret] is empty (the reference refuses to derive without a secret).
 */
fun deriveKartIdentity(masterSecret: ByteArray, crypto: CryptoPrimitives): KartIdentity {
    require(masterSecret.isNotEmpty()) { "the master secret must not be empty" }

    fun derive(name: String, length: Int): ByteArray {
        require(length <= HMAC_SHA512_SIZE)
        return crypto.hmacSha512(key = masterSecret, msg = name.encodeToByteArray()).copyOf(length)
    }

    return KartIdentity(
        gameSsid = GAME_SSID_PREFIX + Base64.Default.encode(derive(LABEL_SSID, SSID_RANDOM_SIZE)),
        gamePsk = derive(LABEL_PSK, PSK_SIZE),
        rcdIdent = derive(LABEL_RCD_IDENT, RCD_IDENT_SIZE),
        rcdMasterKey = derive(LABEL_RCD_MASTER_KEY, RCD_MASTER_KEY_SIZE),
    )
}

/** [deriveKartIdentity] for a string secret, keyed by its UTF-8 bytes as the reference keys its config-file secret. */
fun deriveKartIdentity(masterSecret: String, crypto: CryptoPrimitives): KartIdentity =
    deriveKartIdentity(masterSecret.encodeToByteArray(), crypto)
