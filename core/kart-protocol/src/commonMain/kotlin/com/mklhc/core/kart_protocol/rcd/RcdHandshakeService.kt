// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives

/**
 * The RCD handshake service (service id 1): the four-command state machine that authenticates a
 * device and derives the session it will use.
 *
 * Business logic: the commands must run in order -- begin (1), negotiate-version (2), optionally
 * get-secret-key (3), finalize (4) -- tracked by [nextCmd]; a command out of order is rejected with
 * [RcdErrorHandshakeSequence]. begin binds the device identity and derives its credentials; negotiate
 * picks the best mutually-supported version and, if the device already knows its pairing id, skips
 * straight to finalize (get-secret-key is only for a first-time pairing). finalize proves both sides
 * saw the same conversation: throughout the handshake a running SHA-256 absorbs, per command, the
 * concatenation of the request payload and the response -- but only in whole 64-octet blocks, keeping
 * a short tail buffered. At finalize the device must echo the digest of the blocks absorbed so far
 * (excluding the buffered tail); the server then folds in the tail plus that echoed digest and
 * returns the final digest. This exact block-alignment quirk is reproduced from the reference daemon
 * so an independently-written device interoperates byte-for-byte.
 *
 * Security note: [randomChallenge] supplies the 32-octet challenge sent in the begin response. By
 * default it draws from [crypto]'s cryptographically secure RNG ([CryptoPrimitives.secureRandomBytes]),
 * so production is secure without extra wiring. It stays injectable so tests can pin a fixed challenge
 * and assert the transcript byte-for-byte.
 *
 * @param serverInfo the server identity and the master key credentials derive from.
 * @param peerAddress the transport address the device connected from (recorded on the [RcdDevice]).
 * @param handler optional connect/disconnect callback.
 * @param pairing when true, an unrecognized pairing id is allowed and routed through get-secret-key;
 *   when false (the default), an unrecognized device is rejected.
 * @param crypto the SHA provider for the running handshake hash and the secure source for the default
 *   [randomChallenge].
 * @param randomChallenge supplies the begin-response challenge of the requested length; defaults to a
 *   cryptographically secure draw from [crypto].
 */
class RcdHandshakeService(
    private val serverInfo: RcdServerInfo,
    private val peerAddress: String = "",
    private val handler: RcdDeviceHandler? = null,
    private val pairing: Boolean = false,
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
    private val randomChallenge: (Int) -> ByteArray = { crypto.secureRandomBytes(it) },
) : RcdService {

    override val serviceId: Int = SERVICE_ID

    private val log = Logger.withTag(TAG)

    /** The device built during the handshake; non-null from the begin command onward. */
    var device: RcdDevice? = null
        private set

    private var nextCmd = 1

    // Running handshake hash: [hashed] holds the octets already folded in (always a multiple of 64);
    // [tail] holds the not-yet-aligned remainder. The digest of [hashed] equals a streaming SHA-256
    // fed exactly those octets, so recomputing it each time is equivalent to an incremental hash.
    private var hashed = ByteArray(0)
    private var tail = ByteArray(0)

    override suspend fun dispatch(msg: RcdMsg): ByteArray {
        val result = handleCommand(msg.command, msg.data)
        // Only on success: fold this command's request and response into the running hash, in whole
        // 64-octet blocks (the reference daemon's exact behaviour).
        absorbCommand(msg.data, result)
        return result
    }

    private fun handleCommand(command: Int, data: ByteArray): ByteArray = when (command) {
        CMD_BEGIN -> beginHandshake(data)
        CMD_NEGOTIATE -> negotiateVersion(data)
        CMD_GET_SECRET -> getSecretKey(data)
        CMD_FINALIZE -> finalize(data)
        else -> {
            log.e { "handleCommand(): unknown command -> command=$command" }
            throw RcdErrorBadRequest()
        }
    }

    override fun close() {
        if (nextCmd == COMPLETED) {
            device?.let { handler?.deviceDisconnected(it) }
        }
    }

    private fun beginHandshake(data: ByteArray): ByteArray {
        if (data.size != BEGIN_PAYLOAD_SIZE) {
            log.e { "beginHandshake(): wrong payload size -> size=${data.size} expected=$BEGIN_PAYLOAD_SIZE" }
            throw RcdErrorMissizedPayload()
        }
        if (nextCmd != CMD_BEGIN) {
            log.e { "beginHandshake(): out of sequence -> nextCmd=$nextCmd" }
            throw RcdErrorHandshakeSequence()
        }
        val handshakeVersion = data[0].toInt() and 0xFF
        val deviceNameField = data.copyOfRange(FIELD, FIELD * 2)
        val deviceIdent = data.copyOfRange(FIELD * 2, FIELD * 3)
        if (handshakeVersion != 1) {
            log.e { "beginHandshake(): unsupported handshake version -> version=$handshakeVersion" }
            throw RcdErrorHandshakeVersionMismatch()
        }
        val deviceName = decodeDeviceName(deviceNameField)
        val (pairingId, secretKey) = serverInfo.getPairingKeys(deviceIdent, deviceName)
        device = RcdDevice(
            name = deviceName,
            ident = deviceIdent,
            pairingId = pairingId,
            secretKey = secretKey,
            address = peerAddress,
            version = null,
        )
        nextCmd = CMD_NEGOTIATE
        log.i { "beginHandshake(): accepted begin -> name=$deviceName address=$peerAddress" }
        return padTo(byteArrayOf(handshakeVersion.toByte()), FIELD) +
            padTo(serverInfo.name.encodeToByteArray(), FIELD) +
            serverInfo.ident +
            randomChallenge(CHALLENGE_SIZE)
    }

    private fun negotiateVersion(data: ByteArray): ByteArray {
        if (data.size < PAIRING_ID_SIZE + 1) {
            log.e { "negotiateVersion(): wrong payload size -> size=${data.size}" }
            throw RcdErrorMissizedPayload()
        }
        if (nextCmd != CMD_NEGOTIATE) {
            log.e { "negotiateVersion(): out of sequence -> nextCmd=$nextCmd" }
            throw RcdErrorHandshakeSequence()
        }
        val numVersions = data[PAIRING_ID_SIZE].toInt() and 0xFF
        val offered = data.copyOfRange(PAIRING_ID_SIZE + 1, data.size)
        if (offered.size != numVersions) {
            log.e { "negotiateVersion(): version count mismatch -> declared=$numVersions actual=${offered.size}" }
            throw RcdErrorMissizedPayload()
        }
        val offeredVersions = offered.map { it.toInt() and 0xFF }
        val selected = serverInfo.versions.firstOrNull { it in offeredVersions } ?: run {
            log.e { "negotiateVersion(): no mutually supported version -> offered=$offeredVersions" }
            throw RcdErrorHandshakeBadVersions()
        }
        val device = this.device ?: throw RcdErrorHandshakeSequence()
        val offeredPairingId = data.copyOfRange(0, PAIRING_ID_SIZE)
        nextCmd = when {
            offeredPairingId.contentEquals(device.pairingId) -> CMD_FINALIZE
            !pairing -> {
                log.e { "negotiateVersion(): unrecognized device and pairing is off" }
                throw RcdErrorHandshakeUnrecognizedDevice()
            }
            else -> CMD_GET_SECRET
        }
        this.device = device.copy(version = selected)
        log.i { "negotiateVersion(): selected version -> version=$selected nextCmd=$nextCmd" }
        return device.pairingId + padTo(byteArrayOf(selected.toByte()), FIELD)
    }

    private fun getSecretKey(data: ByteArray): ByteArray {
        if (data.size != PAIRING_ID_SIZE) {
            log.e { "getSecretKey(): wrong payload size -> size=${data.size}" }
            throw RcdErrorMissizedPayload()
        }
        if (nextCmd != CMD_GET_SECRET) {
            log.e { "getSecretKey(): out of sequence -> nextCmd=$nextCmd" }
            throw RcdErrorHandshakeSequence()
        }
        nextCmd = CMD_FINALIZE
        return device!!.secretKey
    }

    private fun finalize(data: ByteArray): ByteArray {
        if (data.size != PAIRING_ID_SIZE) {
            log.e { "finalize(): wrong payload size -> size=${data.size}" }
            throw RcdErrorMissizedPayload()
        }
        if (nextCmd != CMD_FINALIZE) {
            log.e { "finalize(): out of sequence -> nextCmd=$nextCmd" }
            throw RcdErrorHandshakeSequence()
        }
        if (!digestFlushed().contentEquals(data)) {
            log.e { "finalize(): running-hash mismatch -> device=${device?.name}" }
            throw RcdErrorHandshakeHash()
        }
        nextCmd = COMPLETED
        val digest = flushAndAbsorb(data)
        val completed = device!!
        log.i { "finalize(): handshake complete -> name=${completed.name} version=${completed.version}" }
        handler?.deviceConnected(completed)
        return digest
    }

    // ---- running handshake hash ---------------------------------------------------------------

    /** Folds one command's request and response into [hashed], flushing only whole 64-octet blocks. */
    private fun absorbCommand(request: ByteArray, response: ByteArray) {
        tail += request
        tail += response
        if (tail.size >= BLOCK) {
            val aligned = tail.size and (BLOCK - 1).inv()
            hashed += tail.copyOfRange(0, aligned)
            tail = tail.copyOfRange(aligned, tail.size)
        }
    }

    /** The digest of the octets flushed so far (the value the device must echo at finalize). */
    private fun digestFlushed(): ByteArray = crypto.sha256(hashed)

    /** Folds the buffered tail plus [data] into the hash and returns the resulting digest. */
    private fun flushAndAbsorb(data: ByteArray): ByteArray {
        hashed += tail + data
        tail = ByteArray(0)
        return crypto.sha256(hashed)
    }

    private fun decodeDeviceName(field: ByteArray): String {
        val end = field.indexOf(0).let { if (it < 0) field.size else it }
        return field.copyOfRange(0, end).decodeToString()
    }

    private fun padTo(data: ByteArray, length: Int): ByteArray {
        require(data.size <= length) { "cannot pad ${data.size} octets to $length" }
        return if (data.size == length) data else data + ByteArray(length - data.size)
    }

    companion object {
        /** The handshake service id. */
        const val SERVICE_ID = 0x0001

        private const val TAG = "RcdHandshakeService"

        private const val CMD_BEGIN = 1
        private const val CMD_NEGOTIATE = 2
        private const val CMD_GET_SECRET = 3
        private const val CMD_FINALIZE = 4
        private const val COMPLETED = 0

        private const val BLOCK = 64
        private const val CHALLENGE_SIZE = 0x20
        private const val BEGIN_PAYLOAD_SIZE = 0x50
        private const val PAIRING_ID_SIZE = 0x20
        private const val FIELD = 0x10
    }
}
