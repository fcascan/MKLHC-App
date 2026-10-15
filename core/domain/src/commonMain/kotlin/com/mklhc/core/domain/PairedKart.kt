package com.mklhc.core.domain

/**
 * The kart this installation has been paired with.
 *
 * Business logic: after a successful pairing the app remembers which kart it belongs to, so that on
 * the next launch it can recognise that kart on the game network instead of starting a pairing again.
 * The kart is identified by the serial it reports and the 16-octet identity it presents in the RCD
 * handshake. The ident is compared and hashed by content; it is copied in and out so no caller can
 * mutate a stored identity, and it is left out of [toString] so it never reaches a log.
 *
 * @property serial the kart's serial number text.
 * @property ident the kart's 16-octet RCD identity (a copy).
 * @throws IllegalArgumentException if the ident is not [IDENT_SIZE] octets.
 */
class PairedKart(val serial: String, ident: ByteArray) {
    private val identBytes: ByteArray

    init {
        require(ident.size == IDENT_SIZE) { "ident must be $IDENT_SIZE octets, got ${ident.size}" }
        identBytes = ident.copyOf()
    }

    /** The kart's 16-octet RCD identity, a copy. */
    val ident: ByteArray get() = identBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PairedKart && serial == other.serial && identBytes.contentEquals(other.identBytes)

    override fun hashCode(): Int = HASH_PRIME * serial.hashCode() + identBytes.contentHashCode()

    override fun toString(): String = "PairedKart(serial=$serial)"

    companion object {
        /** Octets in an RCD device identity. */
        const val IDENT_SIZE = 16

        private const val HASH_PRIME = 31
    }
}
