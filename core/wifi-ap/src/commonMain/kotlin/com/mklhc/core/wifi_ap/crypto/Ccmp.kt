package com.mklhc.core.wifi_ap.crypto

/** Signals that a received CCMP MPDU could not be accepted (MIC failure or PN replay). */
class CcmpException(message: String) : Exception(message)

/**
 * IEEE 802.11 CCMP (Counter mode with CBC-MAC Protocol) over an 802.11 MPDU.
 *
 * CCMP protects a data MPDU with AES-CCM: CBC-MAC produces an 8-octet MIC over the masked header
 * (AAD) and the frame body, and AES in counter (CTR) mode encrypts the body and the MIC. Because
 * stock JCA exposes no "AES/CCM" transform, AES-CCM is assembled here by hand from the single-block
 * AES primitive in [CryptoPrimitives] (CBC-MAC for the MIC, CTR for encryption), keeping all
 * platform crypto out of commonMain.
 *
 * CCMP uses M = 8 (MIC length) and L = 2 (length-field size), so the CCM nonce is 13 octets. A
 * single [Ccmp] instance tracks the 48-bit packet-number (PN) replay window for the frames it
 * decrypts; a fresh instance accepts any PN. Reset the window with [resetReplay].
 *
 * Implemented from IEEE Std 802.11 CCMP (clause on CCMP / AES-CCM); test vector from
 * IEEE Std 802.11-2012 M.6.4. No third-party code is copied.
 */
class Ccmp(private val primitives: CryptoPrimitives) {

    /** Highest 48-bit PN accepted so far for replay detection; -1 means nothing accepted yet. */
    private var lastAcceptedPn: Long = NO_PN

    /** Clears the replay window so the next received PN is accepted unconditionally. */
    fun resetReplay() {
        lastAcceptedPn = NO_PN
    }

    /**
     * Encrypts a plaintext MPDU and returns the full protected MPDU:
     * `header || ccmpHeader(8) || ciphertext || mic(8)`.
     *
     * @param tk 128-bit temporal key.
     * @param header the cleartext 802.11 MAC header (the Protected Frame bit is expected set).
     * @param plaintext the frame body to protect.
     * @param pn the 48-bit packet number in big-endian order (PN5 first ... PN0 last).
     * @param keyId the key identifier placed in the CCMP header (0..3).
     */
    fun encrypt(
        tk: ByteArray,
        header: ByteArray,
        plaintext: ByteArray,
        pn: ByteArray,
        keyId: Int = 0,
    ): ByteArray {
        require(tk.size == KEY_BYTES) { "TK must be $KEY_BYTES bytes" }
        require(pn.size == PN_BYTES) { "PN must be $PN_BYTES bytes" }

        val nonce = nonce(header, pn)
        val aad = aad(header)

        val s0 = ctrS0(tk, nonce)
        val ciphertext = ctrCrypt(tk, nonce, plaintext)
        val tag = cbcMacTag(tk, nonce, aad, plaintext)
        val mic = ByteArray(MIC_BYTES) { index -> (tag[index].toInt() xor s0[index].toInt()).toByte() }

        return header + ccmpHeader(pn, keyId) + ciphertext + mic
    }

    /**
     * Decrypts a full protected MPDU (`header || ccmpHeader || ciphertext || mic`), verifies the
     * MIC, and enforces the 48-bit PN replay window. Returns the recovered frame body.
     *
     * @throws CcmpException if the PN is a replay or the MIC does not verify.
     */
    fun decrypt(tk: ByteArray, mpdu: ByteArray, keyId: Int = 0): ByteArray {
        require(tk.size == KEY_BYTES) { "TK must be $KEY_BYTES bytes" }

        // Guard before reading Frame Control: the smallest possible CCMP MPDU is a 3-address non-QoS
        // header plus the CCMP header plus the MIC. This keeps macHeaderLength (which reads octets 0
        // and 1) and every later slice in bounds, so a short buffer raises CcmpException, not an
        // IndexOutOfBoundsException.
        if (mpdu.size < MAC_HEADER_BASE_BYTES + CCMP_HEADER_BYTES + MIC_BYTES) {
            throw CcmpException("MPDU too short to be a CCMP frame")
        }

        val headerLength = macHeaderLength(mpdu)
        val minimumLength = headerLength + CCMP_HEADER_BYTES + MIC_BYTES
        if (mpdu.size < minimumLength) {
            throw CcmpException("MPDU too short to be a CCMP frame")
        }

        val header = mpdu.copyOfRange(0, headerLength)
        val ccmpHeader = mpdu.copyOfRange(headerLength, headerLength + CCMP_HEADER_BYTES)
        val ciphertext = mpdu.copyOfRange(headerLength + CCMP_HEADER_BYTES, mpdu.size - MIC_BYTES)
        val receivedMic = mpdu.copyOfRange(mpdu.size - MIC_BYTES, mpdu.size)

        if (ccmpHeader[3].toInt() and EXT_IV != EXT_IV) {
            throw CcmpException("CCMP header does not have the ExtIV bit set")
        }
        val frameKeyId = (ccmpHeader[3].toInt() and 0xFF) ushr 6
        if (frameKeyId != keyId) {
            throw CcmpException("CCMP header key id $frameKeyId does not match expected $keyId")
        }

        val pn = pnFromCcmpHeader(ccmpHeader)
        val pnValue = pnToLong(pn)
        if (pnValue <= lastAcceptedPn) {
            throw CcmpException("PN replay detected")
        }

        val nonce = nonce(header, pn)
        val aad = aad(header)

        val s0 = ctrS0(tk, nonce)
        val plaintext = ctrCrypt(tk, nonce, ciphertext)
        val tag = cbcMacTag(tk, nonce, aad, plaintext)
        val expectedMic = ByteArray(MIC_BYTES) { index -> (tag[index].toInt() xor s0[index].toInt()).toByte() }

        if (!constantTimeEquals(expectedMic, receivedMic)) {
            throw CcmpException("MIC verification failed")
        }

        lastAcceptedPn = pnValue
        return plaintext
    }

    /**
     * Builds the 8-octet CCMP header:
     * `PN0, PN1, 0x00, (keyId shl 6) or ExtIV, PN2, PN3, PN4, PN5`.
     */
    fun ccmpHeader(pn: ByteArray, keyId: Int = 0): ByteArray {
        require(pn.size == PN_BYTES) { "PN must be $PN_BYTES bytes" }
        // pn is big-endian (PN5 first), so PN0 is the last octet.
        return byteArrayOf(
            pn[5],
            pn[4],
            0x00,
            ((keyId shl 6) or EXT_IV).toByte(),
            pn[3],
            pn[2],
            pn[1],
            pn[0],
        )
    }

    /**
     * Builds the 13-octet CCM nonce: `nonceFlags(1) || A2(6) || PN(6, big-endian PN5 first)`.
     * For a data frame with priority 0 the nonce flags octet is 0x00.
     */
    fun nonce(header: ByteArray, pn: ByteArray): ByteArray {
        require(pn.size == PN_BYTES) { "PN must be $PN_BYTES bytes" }
        val nonce = ByteArray(NONCE_BYTES)
        nonce[0] = nonceFlags(header)
        // A2 occupies header octets 10..15.
        header.copyInto(nonce, destinationOffset = 1, startIndex = A2_OFFSET, endIndex = A2_OFFSET + 6)
        // PN is already big-endian (PN5 first), exactly the order CCM expects.
        pn.copyInto(nonce, destinationOffset = 7)
        return nonce
    }

    /**
     * Builds the additional authenticated data (AAD) from the masked MPDU header per IEEE 802.11
     * clause 12.5.3.3.3. For a 3-address non-QoS frame this is 22 octets:
     * `maskedFC(2) || A1 || A2 || A3 || maskedSeqCtrl(2)`. A fourth address and a QoS Control field
     * are appended when present; HT Control, when present, is part of the header but never the AAD.
     *
     * The Frame Control is masked reference-exactly: octet 0 clears the low subtype bits for data
     * frames (keeping the QoS-data subtype bit); octet 1 clears Retry, Power Management and More
     * Data, forces the Protected bit set, and for QoS data clears the Order/+HTC bit. The sequence
     * number is zeroed (the fragment number is kept) and the QoS Control keeps only its TID.
     */
    fun aad(header: ByteArray): ByteArray {
        val fourAddress = isFourAddress(header)
        val qos = isQosData(header)

        var length = AAD_BASE_BYTES
        if (fourAddress) length += 6
        if (qos) length += 2
        val aad = ByteArray(length)

        // Masked Frame Control octet 0: for data frames clear the low subtype bits (4,5,6), keeping
        // version, type and subtype bit 3 so QoS-data (subtype 8) stays distinct from data (0).
        var fc0 = header[0].toInt() and 0xFF
        if (frameType(header) == TYPE_DATA) {
            fc0 = fc0 and FC_MASK_OCTET0
        }
        aad[0] = fc0.toByte()
        // Masked Frame Control octet 1: clear Retry, Power Management and More Data (bits that vary
        // in flight); force Protected; and for QoS data clear the Order/+HTC bit.
        var fc1 = header[1].toInt() and FC_MASK_OCTET1
        fc1 = fc1 or FC_PROTECTED
        if (qos) {
            fc1 = fc1 and ORDER_BIT.inv()
        }
        aad[1] = fc1.toByte()
        // A1, A2, A3 occupy header octets 4..21.
        header.copyInto(aad, destinationOffset = 2, startIndex = 4, endIndex = 22)
        // Masked Sequence Control: keep the fragment number, zero the sequence number.
        aad[20] = (header[22].toInt() and 0x0F).toByte()
        aad[21] = 0x00

        var offset = AAD_BASE_BYTES
        if (fourAddress) {
            header.copyInto(aad, destinationOffset = offset, startIndex = 24, endIndex = 30)
            offset += 6
        }
        if (qos) {
            val qosOffset = if (fourAddress) 30 else 24
            // QoS Control: keep the TID (bits 0..3), mask the rest.
            aad[offset] = (header[qosOffset].toInt() and 0x0F).toByte()
            aad[offset + 1] = 0x00
        }
        return aad
    }

    /** CBC-MAC over B0, the length-prefixed and padded AAD, and the zero-padded plaintext. */
    private fun cbcMacTag(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        var x = primitives.aesEcbEncryptBlock(key, b0(nonce, plaintext.size))

        val formattedAad = formatAad(aad)
        var index = 0
        while (index < formattedAad.size) {
            x = primitives.aesEcbEncryptBlock(key, xorBlock(x, formattedAad, index))
            index += BLOCK_BYTES
        }

        index = 0
        while (index < plaintext.size) {
            x = primitives.aesEcbEncryptBlock(key, xorBlock(x, plaintext, index))
            index += BLOCK_BYTES
        }
        return x.copyOfRange(0, MIC_BYTES)
    }

    /** Encrypts S0 = E(K, A0), the keystream block used to protect the MIC. */
    private fun ctrS0(key: ByteArray, nonce: ByteArray): ByteArray =
        primitives.aesEcbEncryptBlock(key, ctrBlock(nonce, 0))

    /** CTR mode over [input] using counter blocks A1, A2, ...; symmetric for encrypt and decrypt. */
    private fun ctrCrypt(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        var counter = 1
        var index = 0
        while (index < input.size) {
            val keystream = primitives.aesEcbEncryptBlock(key, ctrBlock(nonce, counter))
            val blockEnd = minOf(index + BLOCK_BYTES, input.size)
            var position = index
            var k = 0
            while (position < blockEnd) {
                output[position] = (input[position].toInt() xor keystream[k].toInt()).toByte()
                position++
                k++
            }
            counter++
            index += BLOCK_BYTES
        }
        return output
    }

    /** First CBC-MAC block B0: `flags || nonce || l(m)` with flags = Adata | ((M-2)/2 << 3) | (L-1). */
    private fun b0(nonce: ByteArray, messageLength: Int): ByteArray {
        val block = ByteArray(BLOCK_BYTES)
        block[0] = B0_FLAGS
        nonce.copyInto(block, destinationOffset = 1)
        // L = 2: the message length occupies the final two octets, big-endian.
        block[14] = ((messageLength ushr 8) and 0xFF).toByte()
        block[15] = (messageLength and 0xFF).toByte()
        return block
    }

    /** CTR counter block Ai: `flags(L-1) || nonce || i` with i as a two-octet big-endian counter. */
    private fun ctrBlock(nonce: ByteArray, counter: Int): ByteArray {
        val block = ByteArray(BLOCK_BYTES)
        block[0] = CTR_FLAGS
        nonce.copyInto(block, destinationOffset = 1)
        block[14] = ((counter ushr 8) and 0xFF).toByte()
        block[15] = (counter and 0xFF).toByte()
        return block
    }

    /** Prefixes the AAD with its two-octet length and zero-pads to a multiple of the block size. */
    private fun formatAad(aad: ByteArray): ByteArray {
        val prefixed = ByteArray(2 + aad.size)
        prefixed[0] = ((aad.size ushr 8) and 0xFF).toByte()
        prefixed[1] = (aad.size and 0xFF).toByte()
        aad.copyInto(prefixed, destinationOffset = 2)
        val paddedLength = ((prefixed.size + BLOCK_BYTES - 1) / BLOCK_BYTES) * BLOCK_BYTES
        return prefixed.copyOf(paddedLength)
    }

    /** XORs the 16-octet [state] with the block at [offset] in [source], zero-padding past its end. */
    private fun xorBlock(state: ByteArray, source: ByteArray, offset: Int): ByteArray {
        val block = ByteArray(BLOCK_BYTES)
        var i = 0
        while (i < BLOCK_BYTES) {
            val sourceByte = if (offset + i < source.size) source[offset + i].toInt() else 0
            block[i] = (state[i].toInt() xor sourceByte).toByte()
            i++
        }
        return block
    }

    /** Computes the nonce flags octet: TID for QoS frames, the management bit for management frames. */
    private fun nonceFlags(header: ByteArray): Byte {
        var flags = 0
        if (isQosData(header)) {
            val qosOffset = if (isFourAddress(header)) 30 else 24
            flags = flags or (header[qosOffset].toInt() and 0x0F)
        }
        if (frameType(header) == TYPE_MANAGEMENT) {
            flags = flags or NONCE_MANAGEMENT_BIT
        }
        return flags.toByte()
    }

    /** Derives the MAC header length from Frame Control (3/4 addresses, QoS Control, HT Control). */
    private fun macHeaderLength(header: ByteArray): Int {
        // Base 3-address MAC header: FC(2) + Duration(2) + A1(6) + A2(6) + A3(6) + SeqCtrl(2).
        var length = MAC_HEADER_BASE_BYTES
        if (isFourAddress(header)) length += 6
        if (isQosData(header)) {
            length += 2
            // The Order bit signals +HTC (HT Control, 4 octets) for QoS frames.
            if (header[1].toInt() and ORDER_BIT != 0) length += 4
        }
        return length
    }

    /** Rebuilds the big-endian PN (PN5 first) from the CCMP header octets. */
    private fun pnFromCcmpHeader(ccmpHeader: ByteArray): ByteArray =
        byteArrayOf(
            ccmpHeader[7],
            ccmpHeader[6],
            ccmpHeader[5],
            ccmpHeader[4],
            ccmpHeader[1],
            ccmpHeader[0],
        )

    private fun pnToLong(pn: ByteArray): Long {
        var value = 0L
        for (octet in pn) {
            value = (value shl 8) or (octet.toLong() and 0xFF)
        }
        return value
    }

    private fun frameType(header: ByteArray): Int = (header[0].toInt() ushr 2) and 0x03

    private fun frameSubtype(header: ByteArray): Int = (header[0].toInt() ushr 4) and 0x0F

    private fun isQosData(header: ByteArray): Boolean =
        frameType(header) == TYPE_DATA && (frameSubtype(header) and QOS_SUBTYPE_BIT) != 0

    private fun isFourAddress(header: ByteArray): Boolean {
        val toDs = header[1].toInt() and 0x01 != 0
        val fromDs = header[1].toInt() and 0x02 != 0
        return toDs && fromDs
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].toInt() xor b[index].toInt())
        }
        return difference == 0
    }

    private companion object {
        const val KEY_BYTES = 16
        const val PN_BYTES = 6
        const val BLOCK_BYTES = 16
        const val NONCE_BYTES = 13
        const val MIC_BYTES = 8
        const val CCMP_HEADER_BYTES = 8
        const val AAD_BASE_BYTES = 22
        const val MAC_HEADER_BASE_BYTES = 24
        const val A2_OFFSET = 10
        const val NO_PN = -1L

        // CCMP uses M = 8 and L = 2: B0 flags = Adata(0x40) | ((8-2)/2 << 3) | (2-1) = 0x59.
        const val B0_FLAGS: Byte = 0x59
        // CTR flags = L - 1 = 1.
        const val CTR_FLAGS: Byte = 0x01
        // ExtIV bit in CCMP header octet 3.
        const val EXT_IV = 0x20
        // Frame Control octet-0 mask clearing the low subtype bits(0x70) for data frames, keeping
        // version, type and subtype bit 3 (0x8F = 1000 1111).
        const val FC_MASK_OCTET0 = 0x8F
        // Frame Control octet-1 mask clearing Retry(0x08), PwrMgmt(0x10) and More Data(0x20).
        const val FC_MASK_OCTET1 = 0xC7
        // Protected bit in Frame Control octet 1; forced set in the AAD.
        const val FC_PROTECTED = 0x40
        const val ORDER_BIT = 0x80
        const val NONCE_MANAGEMENT_BIT = 0x10
        const val TYPE_MANAGEMENT = 0
        const val TYPE_DATA = 2
        const val QOS_SUBTYPE_BIT = 0x08
    }
}
