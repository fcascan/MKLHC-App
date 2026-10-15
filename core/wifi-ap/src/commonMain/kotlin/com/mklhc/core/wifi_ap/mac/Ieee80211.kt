package com.mklhc.core.wifi_ap.mac

/*
 * IEEE 802.11 frame codec (management and data frames).
 *
 * Implemented from the IEEE 802.11 standard ("IEEE 802.11 frame formats": MAC header, Frame Control,
 * Sequence Control, management frame bodies, information elements) and IEEE 802.2 SNAP. Original
 * code; no third-party source was consulted or copied. All multi-octet MAC header and fixed-field
 * values are little-endian on the wire; addresses are transmitted in order; the LLC/SNAP EtherType
 * is big-endian.
 */

/**
 * A 48-bit IEEE 802 MAC address, compared by value.
 *
 * Business logic: the AP identifies the BSSID and stations by address, so addresses must support
 * equality and hashing (a raw `ByteArray` compares by reference). Instances are immutable; the
 * backing octets are copied on the way in and out.
 */
class MacAddress private constructor(private val octets: ByteArray) {

    /** True when the group (I/G) bit of the first octet is set; broadcast is also multicast. */
    val isMulticast: Boolean get() = octets[0].toInt() and GROUP_BIT != 0

    /** True for ff:ff:ff:ff:ff:ff. */
    val isBroadcast: Boolean get() = octets.all { it == 0xFF.toByte() }

    /** A copy of the six octets in transmission order. */
    fun toByteArray(): ByteArray = octets.copyOf()

    override fun equals(other: Any?): Boolean = other is MacAddress && octets.contentEquals(other.octets)

    override fun hashCode(): Int = octets.contentHashCode()

    /** Lower-case colon-separated hex, for example `02:11:22:33:44:55`. */
    override fun toString(): String =
        octets.joinToString(":") { (it.toInt() and 0xFF).toString(HEX_RADIX).padStart(2, '0') }

    companion object {
        /** Number of octets in a MAC address. */
        const val SIZE = 6

        private const val GROUP_BIT = 0x01
        private const val HEX_RADIX = 16

        /** The broadcast address ff:ff:ff:ff:ff:ff. */
        val BROADCAST: MacAddress = MacAddress(ByteArray(SIZE) { 0xFF.toByte() })

        /** Reads the six octets starting at [offset] of [bytes]. */
        fun fromBytes(bytes: ByteArray, offset: Int = 0): MacAddress {
            require(offset >= 0 && bytes.size - offset >= SIZE) { "need $SIZE octets at offset $offset" }
            return MacAddress(bytes.copyOfRange(offset, offset + SIZE))
        }

        /** Parses `aa:bb:cc:dd:ee:ff` (case-insensitive). */
        fun parse(text: String): MacAddress {
            val parts = text.split(':')
            require(parts.size == SIZE) { "a MAC address has $SIZE colon-separated octets: $text" }
            return MacAddress(
                ByteArray(SIZE) { index ->
                    val part = parts[index]
                    val value = if (part.length in 1..2) part.toIntOrNull(HEX_RADIX) else null
                    (value ?: throw IllegalArgumentException("bad MAC address octet '$part' in $text")).toByte()
                },
            )
        }
    }
}

/**
 * The 16-bit 802.11 Frame Control field.
 *
 * Business logic: it tells the AP what a received frame is (type and subtype) and which
 * direction and protection flags it carries. [value] is the little-endian 16-bit number whose
 * low octet holds protocol version, type and subtype and whose high octet holds the flag bits.
 */
data class FrameControl(val value: Int) {

    init {
        require(value in 0..MAX_VALUE) { "frame control must fit 16 bits: $value" }
    }

    val protocolVersion: Int get() = value and 0x3
    val type: Int get() = (value shr 2) and 0x3
    val subtype: Int get() = (value shr 4) and 0xF
    val toDs: Boolean get() = value and TO_DS != 0
    val fromDs: Boolean get() = value and FROM_DS != 0
    val moreFragments: Boolean get() = value and MORE_FRAGMENTS != 0
    val retry: Boolean get() = value and RETRY != 0
    val powerManagement: Boolean get() = value and POWER_MANAGEMENT != 0
    val moreData: Boolean get() = value and MORE_DATA != 0
    val protectedFrame: Boolean get() = value and PROTECTED != 0
    val order: Boolean get() = value and ORDER != 0

    /** The two on-wire octets, low octet first. */
    fun toBytes(): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    companion object {
        private const val MAX_VALUE = 0xFFFF
        private const val TO_DS = 0x0100
        private const val FROM_DS = 0x0200
        private const val MORE_FRAGMENTS = 0x0400
        private const val RETRY = 0x0800
        private const val POWER_MANAGEMENT = 0x1000
        private const val MORE_DATA = 0x2000
        private const val PROTECTED = 0x4000
        private const val ORDER = 0x8000

        /** Decodes the two octets at [offset] of [bytes]. */
        fun fromBytes(bytes: ByteArray, offset: Int = 0): FrameControl =
            FrameControl((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8))

        /** Composes a protocol-version-0 Frame Control from its parts. */
        fun of(
            type: Int,
            subtype: Int,
            toDs: Boolean = false,
            fromDs: Boolean = false,
            moreFragments: Boolean = false,
            retry: Boolean = false,
            powerManagement: Boolean = false,
            moreData: Boolean = false,
            protectedFrame: Boolean = false,
            order: Boolean = false,
        ): FrameControl {
            require(type in 0..3) { "type must be 0..3: $type" }
            require(subtype in 0..15) { "subtype must be 0..15: $subtype" }
            var composed = (type shl 2) or (subtype shl 4)
            if (toDs) composed = composed or TO_DS
            if (fromDs) composed = composed or FROM_DS
            if (moreFragments) composed = composed or MORE_FRAGMENTS
            if (retry) composed = composed or RETRY
            if (powerManagement) composed = composed or POWER_MANAGEMENT
            if (moreData) composed = composed or MORE_DATA
            if (protectedFrame) composed = composed or PROTECTED
            if (order) composed = composed or ORDER
            return FrameControl(composed)
        }
    }
}

/**
 * The 16-bit 802.11 Sequence Control field: a 12-bit sequence number and a 4-bit fragment number.
 *
 * Business logic: the AP numbers the frames it transmits with an increasing sequence number so the
 * station can discard duplicates; received frames carry the station's number.
 */
data class SequenceControl(val value: Int) {

    init {
        require(value in 0..0xFFFF) { "sequence control must fit 16 bits: $value" }
    }

    val sequenceNumber: Int get() = value shr 4
    val fragmentNumber: Int get() = value and 0xF

    /** The two on-wire octets, low octet first. */
    fun toBytes(): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    companion object {
        /** Builds the field from [sequenceNumber] (taken modulo 4096) and [fragmentNumber] (modulo 16). */
        fun of(sequenceNumber: Int, fragmentNumber: Int = 0): SequenceControl =
            SequenceControl(((sequenceNumber and 0xFFF) shl 4) or (fragmentNumber and 0xF))

        /** Decodes the two octets at [offset] of [bytes]. */
        fun fromBytes(bytes: ByteArray, offset: Int = 0): SequenceControl =
            SequenceControl((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8))
    }
}

/** An 802.11 information element: a one-octet id, a one-octet length and up to 255 octets of value. */
class InformationElement(val id: Int, value: ByteArray) {

    /** The element value (a defensive copy is kept). */
    val value: ByteArray = value.copyOf()

    init {
        require(id in 0..255) { "element id must be 0..255: $id" }
        require(value.size <= InformationElements.MAX_VALUE_BYTES) { "element value too long: ${value.size}" }
    }

    /** The on-wire TLV: `id, length, value`. */
    fun encode(): ByteArray = byteArrayOf(id.toByte(), value.size.toByte()) + value

    override fun equals(other: Any?): Boolean =
        other is InformationElement && id == other.id && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * id + value.contentHashCode()

    override fun toString(): String = "InformationElement(id=$id, length=${value.size})"
}

/**
 * Information element helpers: generic TLV build/parse plus builders for the elements the AP emits.
 *
 * Element ids per IEEE 802.11: SSID 0, Supported Rates 1, DS Parameter Set 3, TIM 5, ERP 42, RSN
 * 48, Extended Supported Rates 50, Vendor Specific 221.
 */
object InformationElements {

    const val SSID = 0
    const val SUPPORTED_RATES = 1
    const val DS_PARAMETER_SET = 3
    const val TIM = 5
    const val ERP = 42
    const val RSN = 48
    const val EXTENDED_SUPPORTED_RATES = 50
    const val VENDOR_SPECIFIC = 221

    internal const val MAX_VALUE_BYTES = 255
    private const val MAX_SSID_BYTES = 32
    private const val MAX_SUPPORTED_RATES = 8
    private const val MAX_TIM_BITMAP_BYTES = 251

    /** Encodes one TLV: `id, length, value`. */
    fun build(id: Int, value: ByteArray): ByteArray = InformationElement(id, value).encode()

    /**
     * Parses a run of TLVs starting at [offset]. Parsing is tolerant: it stops at the first element
     * whose declared length runs past the end of [bytes] (or at a dangling single octet) and
     * returns the elements read before that point.
     */
    fun parse(bytes: ByteArray, offset: Int = 0): List<InformationElement> {
        val elements = ArrayList<InformationElement>()
        var position = offset
        while (position + 2 <= bytes.size) {
            val id = bytes[position].toInt() and 0xFF
            val length = bytes[position + 1].toInt() and 0xFF
            val valueStart = position + 2
            if (valueStart + length > bytes.size) break
            elements.add(InformationElement(id, bytes.copyOfRange(valueStart, valueStart + length)))
            position = valueStart + length
        }
        return elements
    }

    /** Returns the first element with the given [id], or null. */
    fun find(elements: List<InformationElement>, id: Int): InformationElement? = elements.firstOrNull { it.id == id }

    /** SSID element (id 0) carrying [ssid] (0..32 octets; an empty SSID is the wildcard). */
    fun ssid(ssid: ByteArray): ByteArray {
        require(ssid.size <= MAX_SSID_BYTES) { "SSID is at most $MAX_SSID_BYTES octets: ${ssid.size}" }
        return build(SSID, ssid)
    }

    /** Supported Rates element (id 1), 1..8 rate octets (units of 500 kbit/s, bit 7 = basic rate). */
    fun supportedRates(rates: ByteArray): ByteArray {
        require(rates.size in 1..MAX_SUPPORTED_RATES) { "Supported Rates holds 1..$MAX_SUPPORTED_RATES rates" }
        return build(SUPPORTED_RATES, rates)
    }

    /** DS Parameter Set element (id 3) announcing the current [channel] (1..14). */
    fun dsParameterSet(channel: Int): ByteArray {
        require(channel in 1..MAX_CHANNEL) { "channel must be 1..$MAX_CHANNEL: $channel" }
        return build(DS_PARAMETER_SET, byteArrayOf(channel.toByte()))
    }

    /**
     * TIM element (id 5): DTIM count, DTIM period, bitmap control and a 1..251 octet partial
     * virtual bitmap. A beacon with no buffered traffic carries `00 01 00 00`.
     */
    fun tim(
        dtimCount: Int,
        dtimPeriod: Int,
        bitmapControl: Int,
        partialVirtualBitmap: ByteArray = byteArrayOf(0),
    ): ByteArray {
        require(partialVirtualBitmap.size in 1..MAX_TIM_BITMAP_BYTES) { "TIM bitmap is 1..$MAX_TIM_BITMAP_BYTES octets" }
        return build(
            TIM,
            byteArrayOf(dtimCount.toByte(), dtimPeriod.toByte(), bitmapControl.toByte()) + partialVirtualBitmap,
        )
    }

    /** ERP element (id 42): bit 0 Non-ERP present, bit 1 Use Protection, bit 2 Barker preamble. */
    fun erp(info: Int): ByteArray = build(ERP, byteArrayOf(info.toByte()))

    /** Extended Supported Rates element (id 50) carrying the rates beyond the first eight. */
    fun extendedSupportedRates(rates: ByteArray): ByteArray {
        require(rates.isNotEmpty()) { "Extended Supported Rates needs at least one rate" }
        return build(EXTENDED_SUPPORTED_RATES, rates)
    }

    private const val MAX_CHANNEL = 14
}

/** Fixed fields of an authentication frame body, followed by its elements. */
class AuthenticationFields(
    val algorithm: Int,
    val transactionSequence: Int,
    val statusCode: Int,
    val elements: List<InformationElement>,
)

/** Fixed fields of an association or reassociation request. [currentAp] is set only for a reassociation. */
class AssociationRequestFields(
    val capabilityInfo: Int,
    val listenInterval: Int,
    val currentAp: MacAddress?,
    val elements: List<InformationElement>,
)

/** Fixed fields of an association or reassociation response; [aid] is the AID with the two high bits stripped. */
class AssociationResponseFields(
    val capabilityInfo: Int,
    val statusCode: Int,
    val aid: Int,
    val elements: List<InformationElement>,
)

/** Fixed fields of a beacon or probe response body, followed by its elements. */
class BeaconFields(
    val timestamp: Long,
    val beaconIntervalTu: Int,
    val capabilityInfo: Int,
    val elements: List<InformationElement>,
)

/**
 * A parsed 802.11 management or data frame (FCS stripped).
 *
 * Business logic: the AP receives raw frames from the radio and must classify them (probe, auth,
 * association, deauthentication, data) and read their addresses and fixed fields. The model keeps
 * the header exactly as received so [toBytes] reproduces the original octets, which is what the
 * round-trip tests and packet capture rely on.
 *
 * Layout handled: the 24-octet 3-address header, an optional fourth address (to-DS and from-DS
 * both set, data frames only), an optional 2-octet QoS Control (QoS data subtypes) and, for QoS
 * frames with the Order bit set, a 4-octet HT Control. Control frames (ACK, RTS, ...) are not
 * modelled; [parse] returns null for them.
 */
class Ieee80211Frame(
    val frameControl: FrameControl,
    val addr1: MacAddress,
    val addr2: MacAddress,
    val addr3: MacAddress,
    val durationId: Int = 0,
    val sequenceControl: SequenceControl = SequenceControl(0),
    val addr4: MacAddress? = null,
    val qosControl: Int? = null,
    val htControl: Int? = null,
    body: ByteArray = ByteArray(0),
) {

    /** The frame body (everything after the MAC header; for a protected frame this is CCMP header + ciphertext + MIC). */
    val body: ByteArray = body.copyOf()

    /** Length in octets of the MAC header as serialised by [toBytes]. */
    val headerLength: Int
        get() = BASE_HEADER_BYTES +
            (if (addr4 != null) MacAddress.SIZE else 0) +
            (if (qosControl != null) QOS_CONTROL_BYTES else 0) +
            (if (htControl != null) HT_CONTROL_BYTES else 0)

    val isManagement: Boolean get() = frameControl.type == Ieee80211.TYPE_MANAGEMENT
    val isDataFrame: Boolean get() = frameControl.type == Ieee80211.TYPE_DATA

    /** True for any QoS data subtype (QoS Data, QoS Null, ...). */
    val isQosData: Boolean get() = isDataFrame && frameControl.subtype and QOS_SUBTYPE_BIT != 0

    /** True for the Null Function and QoS Null Function subtypes (power-save signalling, no payload). */
    val isNullData: Boolean
        get() = isDataFrame &&
            (frameControl.subtype == Ieee80211.SUBTYPE_NULL || frameControl.subtype == Ieee80211.SUBTYPE_QOS_NULL)

    val toDs: Boolean get() = frameControl.toDs
    val fromDs: Boolean get() = frameControl.fromDs
    val retry: Boolean get() = frameControl.retry
    val isProtected: Boolean get() = frameControl.protectedFrame

    val isProbeRequest: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_PROBE_REQUEST)
    val isProbeResponse: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_PROBE_RESPONSE)
    val isBeacon: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_BEACON)
    val isAuth: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_AUTH)
    val isAssocRequest: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_ASSOC_REQUEST)
    val isAssocResponse: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_ASSOC_RESPONSE)
    val isReassocRequest: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_REASSOC_REQUEST)
    val isReassocResponse: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_REASSOC_RESPONSE)
    val isDeauth: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_DEAUTH)
    val isDisassoc: Boolean get() = isManagementSubtype(Ieee80211.SUBTYPE_DISASSOC)

    /** The elements of a probe request body (which is nothing but elements); empty for other frames. */
    fun probeRequestElements(): List<InformationElement> =
        if (isProbeRequest) InformationElements.parse(body) else emptyList()

    /** The authentication fixed fields and elements, or null when this is not a well-formed authentication frame. */
    fun authentication(): AuthenticationFields? {
        if (!isAuth || body.size < AUTH_FIXED_BYTES) return null
        return AuthenticationFields(
            algorithm = body.le16(0),
            transactionSequence = body.le16(2),
            statusCode = body.le16(4),
            elements = InformationElements.parse(body, AUTH_FIXED_BYTES),
        )
    }

    /** The (re)association request fields, or null when this is not a well-formed (re)association request. */
    fun associationRequest(): AssociationRequestFields? {
        val reassociation = isReassocRequest
        if (!isAssocRequest && !reassociation) return null
        val fixedBytes = ASSOC_REQUEST_FIXED_BYTES + if (reassociation) MacAddress.SIZE else 0
        if (body.size < fixedBytes) return null
        return AssociationRequestFields(
            capabilityInfo = body.le16(0),
            listenInterval = body.le16(2),
            currentAp = if (reassociation) MacAddress.fromBytes(body, ASSOC_REQUEST_FIXED_BYTES) else null,
            elements = InformationElements.parse(body, fixedBytes),
        )
    }

    /** The (re)association response fields, or null when this is not a well-formed (re)association response. */
    fun associationResponseFields(): AssociationResponseFields? {
        if ((!isAssocResponse && !isReassocResponse) || body.size < ASSOC_RESPONSE_FIXED_BYTES) return null
        return AssociationResponseFields(
            capabilityInfo = body.le16(0),
            statusCode = body.le16(2),
            aid = body.le16(4) and AID_MASK,
            elements = InformationElements.parse(body, ASSOC_RESPONSE_FIXED_BYTES),
        )
    }

    /** The beacon / probe response fixed fields and elements, or null for other or truncated frames. */
    fun beaconFields(): BeaconFields? {
        if ((!isBeacon && !isProbeResponse) || body.size < BEACON_FIXED_BYTES) return null
        return BeaconFields(
            timestamp = body.le64(0),
            beaconIntervalTu = body.le16(8),
            capabilityInfo = body.le16(10),
            elements = InformationElements.parse(body, BEACON_FIXED_BYTES),
        )
    }

    /** The reason code of a deauthentication or disassociation frame, or null for other frames. */
    fun reasonCode(): Int? = if ((isDeauth || isDisassoc) && body.size >= REASON_BYTES) body.le16(0) else null

    /** Serialises the frame: MAC header (little-endian fields) followed by the body. */
    fun toBytes(): ByteArray {
        val writer = ByteWriter(headerLength + body.size)
        writer.bytes(frameControl.toBytes())
        writer.le16(durationId)
        writer.bytes(addr1.toByteArray())
        writer.bytes(addr2.toByteArray())
        writer.bytes(addr3.toByteArray())
        writer.bytes(sequenceControl.toBytes())
        addr4?.let { writer.bytes(it.toByteArray()) }
        qosControl?.let { writer.le16(it) }
        htControl?.let { writer.le32(it) }
        writer.bytes(body)
        return writer.toByteArray()
    }

    private fun isManagementSubtype(subtype: Int): Boolean = isManagement && frameControl.subtype == subtype

    companion object {
        private const val BASE_HEADER_BYTES = 24
        private const val QOS_CONTROL_BYTES = 2
        private const val HT_CONTROL_BYTES = 4
        private const val QOS_SUBTYPE_BIT = 0x8
        private const val AUTH_FIXED_BYTES = 6
        private const val ASSOC_REQUEST_FIXED_BYTES = 4
        private const val ASSOC_RESPONSE_FIXED_BYTES = 6
        private const val BEACON_FIXED_BYTES = 12
        private const val REASON_BYTES = 2
        private const val AID_MASK = 0x3FFF

        /**
         * Parses [bytes] (no FCS) into a frame, or returns null when the octets are not a
         * management or data frame of protocol version 0, or are too short for the header their
         * Frame Control declares.
         */
        fun parse(bytes: ByteArray): Ieee80211Frame? {
            if (bytes.size < BASE_HEADER_BYTES) return null
            val frameControl = FrameControl.fromBytes(bytes, 0)
            if (frameControl.protocolVersion != 0) return null
            val isData = frameControl.type == Ieee80211.TYPE_DATA
            if (frameControl.type != Ieee80211.TYPE_MANAGEMENT && !isData) return null

            var offset = BASE_HEADER_BYTES
            var addr4: MacAddress? = null
            if (isData && frameControl.toDs && frameControl.fromDs) {
                if (bytes.size < offset + MacAddress.SIZE) return null
                addr4 = MacAddress.fromBytes(bytes, offset)
                offset += MacAddress.SIZE
            }
            var qosControl: Int? = null
            var htControl: Int? = null
            if (isData && frameControl.subtype and QOS_SUBTYPE_BIT != 0) {
                if (bytes.size < offset + QOS_CONTROL_BYTES) return null
                qosControl = bytes.le16(offset)
                offset += QOS_CONTROL_BYTES
                // The Order bit on a QoS frame signals a trailing HT Control field.
                if (frameControl.order) {
                    if (bytes.size < offset + HT_CONTROL_BYTES) return null
                    htControl = bytes.le32(offset)
                    offset += HT_CONTROL_BYTES
                }
            }

            return Ieee80211Frame(
                frameControl = frameControl,
                addr1 = MacAddress.fromBytes(bytes, 4),
                addr2 = MacAddress.fromBytes(bytes, 10),
                addr3 = MacAddress.fromBytes(bytes, 16),
                durationId = bytes.le16(2),
                sequenceControl = SequenceControl.fromBytes(bytes, 22),
                addr4 = addr4,
                qosControl = qosControl,
                htControl = htControl,
                body = bytes.copyOfRange(offset, bytes.size),
            )
        }
    }
}

/** IEEE 802.11 status codes used by the AP (clause on status codes). */
object StatusCodes {
    const val SUCCESS = 0
    const val UNSPECIFIED_FAILURE = 1
    const val UNSUPPORTED_AUTH_ALGORITHM = 13
    const val UNKNOWN_AUTH_TRANSACTION = 14

    /** "Association denied because the AP is unable to handle additional associated STAs". */
    const val AP_FULL = 17
}

/** IEEE 802.11 reason codes used by the AP (clause on reason codes). */
object ReasonCodes {
    const val UNSPECIFIED = 1
    const val PREVIOUS_AUTH_NO_LONGER_VALID = 2
    const val DEAUTH_LEAVING = 3
    const val DISASSOC_INACTIVITY = 4
    const val CLASS2_FRAME_FROM_NONAUTH_STA = 6
    const val CLASS3_FRAME_FROM_NONASSOC_STA = 7
    const val DISASSOC_LEAVING = 8
}

/** The decoded LLC/SNAP header of a data frame body: the EtherType and the payload that follows. */
class LlcSnapPayload(val etherType: Int, val payload: ByteArray)

/**
 * IEEE 802.2 LLC with a SNAP header (OUI 00:00:00), the encapsulation 802.11 data frames use to
 * carry an Ethernet payload: `AA AA 03 00 00 00 <EtherType big-endian> <payload>`.
 */
object LlcSnap {

    private val PREFIX = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00)
    private const val HEADER_BYTES = 8

    /** Prepends the LLC/SNAP header for [etherType] to [payload]. */
    fun wrap(etherType: Int, payload: ByteArray): ByteArray {
        require(etherType in 0..0xFFFF) { "EtherType must fit 16 bits: $etherType" }
        return PREFIX + byteArrayOf((etherType shr 8).toByte(), etherType.toByte()) + payload
    }

    /** Splits an LLC/SNAP-encapsulated [body], or returns null when it does not start with the SNAP header. */
    fun unwrap(body: ByteArray): LlcSnapPayload? {
        if (body.size < HEADER_BYTES) return null
        for (index in PREFIX.indices) {
            if (body[index] != PREFIX[index]) return null
        }
        val etherType = ((body[6].toInt() and 0xFF) shl 8) or (body[7].toInt() and 0xFF)
        return LlcSnapPayload(etherType, body.copyOfRange(HEADER_BYTES, body.size))
    }
}

/**
 * Builders for the 802.11 frames an access point transmits, plus the type/subtype constants.
 *
 * Business logic: every management frame the AP sends has addr1 = the station (or broadcast for a
 * beacon), addr2 = addr3 = the BSSID, a Frame Control for the right subtype and a fixed-fields +
 * information-element body. The data plane uses a from-DS data frame: addr1 = destination
 * station, addr2 = BSSID (transmitter), addr3 = source address. Builders return the MPDU without
 * FCS; the radio appends it. Sequence numbers are caller-supplied (taken modulo 4096).
 */
object Ieee80211 {

    const val TYPE_MANAGEMENT = 0
    const val TYPE_CONTROL = 1
    const val TYPE_DATA = 2

    const val SUBTYPE_ASSOC_REQUEST = 0
    const val SUBTYPE_ASSOC_RESPONSE = 1
    const val SUBTYPE_REASSOC_REQUEST = 2
    const val SUBTYPE_REASSOC_RESPONSE = 3
    const val SUBTYPE_PROBE_REQUEST = 4
    const val SUBTYPE_PROBE_RESPONSE = 5
    const val SUBTYPE_BEACON = 8
    const val SUBTYPE_DISASSOC = 10
    const val SUBTYPE_AUTH = 11
    const val SUBTYPE_DEAUTH = 12

    const val SUBTYPE_DATA = 0
    const val SUBTYPE_NULL = 4
    const val SUBTYPE_QOS_DATA = 8
    const val SUBTYPE_QOS_NULL = 12

    /** Open System authentication algorithm number. */
    const val AUTH_ALGORITHM_OPEN_SYSTEM = 0

    /** The two high bits the standard sets in the Association Response AID field. */
    private const val AID_FIELD_FLAGS = 0xC000
    private const val AID_MASK = 0x3FFF

    /** Generic management frame: header with [addr1]/[addr2]/[addr3] and subtype, followed by [body]. */
    fun managementFrame(
        subtype: Int,
        addr1: MacAddress,
        addr2: MacAddress,
        addr3: MacAddress,
        body: ByteArray,
        sequenceNumber: Int = 0,
    ): ByteArray = Ieee80211Frame(
        frameControl = FrameControl.of(TYPE_MANAGEMENT, subtype),
        addr1 = addr1,
        addr2 = addr2,
        addr3 = addr3,
        sequenceControl = SequenceControl.of(sequenceNumber),
        body = body,
    ).toBytes()

    /**
     * Beacon: broadcast addr1, addr2 = addr3 = [bssid], body = timestamp(8) + interval(2) +
     * capability(2) + [elements]. The timestamp is normally 0 because the radio overwrites it.
     */
    fun beacon(
        bssid: MacAddress,
        timestamp: Long,
        beaconIntervalTu: Int,
        capabilityInfo: Int,
        elements: ByteArray,
        sequenceNumber: Int = 0,
    ): ByteArray = managementFrame(
        SUBTYPE_BEACON,
        MacAddress.BROADCAST,
        bssid,
        bssid,
        beaconBody(timestamp, beaconIntervalTu, capabilityInfo, elements),
        sequenceNumber,
    )

    /** Probe response to [station]: same body layout as a beacon. */
    fun probeResponse(
        station: MacAddress,
        bssid: MacAddress,
        timestamp: Long,
        beaconIntervalTu: Int,
        capabilityInfo: Int,
        elements: ByteArray,
        sequenceNumber: Int = 0,
    ): ByteArray = managementFrame(
        SUBTYPE_PROBE_RESPONSE,
        station,
        bssid,
        bssid,
        beaconBody(timestamp, beaconIntervalTu, capabilityInfo, elements),
        sequenceNumber,
    )

    /** Authentication frame to [station]: body = algorithm(2) + transaction sequence(2) + status(2) + [elements]. */
    fun authenticationResponse(
        station: MacAddress,
        bssid: MacAddress,
        algorithm: Int,
        transactionSequence: Int,
        statusCode: Int,
        elements: ByteArray = ByteArray(0),
        sequenceNumber: Int = 0,
    ): ByteArray {
        val body = ByteWriter(AUTH_BODY_FIXED_BYTES + elements.size)
        body.le16(algorithm)
        body.le16(transactionSequence)
        body.le16(statusCode)
        body.bytes(elements)
        return managementFrame(SUBTYPE_AUTH, station, bssid, bssid, body.toByteArray(), sequenceNumber)
    }

    /**
     * (Re)association response to [station]: body = capability(2) + status(2) + AID(2) + [elements].
     * The AID is transmitted with its two high bits set, as the standard requires (AID 1 becomes
     * `0xC001`). Pass [reassociation] = true to answer a reassociation request.
     */
    fun associationResponse(
        station: MacAddress,
        bssid: MacAddress,
        capabilityInfo: Int,
        statusCode: Int,
        aid: Int,
        elements: ByteArray,
        reassociation: Boolean = false,
        sequenceNumber: Int = 0,
    ): ByteArray {
        val body = ByteWriter(ASSOC_RESPONSE_FIXED_BYTES + elements.size)
        body.le16(capabilityInfo)
        body.le16(statusCode)
        body.le16((aid and AID_MASK) or AID_FIELD_FLAGS)
        body.bytes(elements)
        val subtype = if (reassociation) SUBTYPE_REASSOC_RESPONSE else SUBTYPE_ASSOC_RESPONSE
        return managementFrame(subtype, station, bssid, bssid, body.toByteArray(), sequenceNumber)
    }

    /** Deauthentication frame to [station] with [reasonCode]. */
    fun deauthentication(station: MacAddress, bssid: MacAddress, reasonCode: Int, sequenceNumber: Int = 0): ByteArray =
        managementFrame(SUBTYPE_DEAUTH, station, bssid, bssid, reasonBody(reasonCode), sequenceNumber)

    /** Disassociation frame to [station] with [reasonCode]. */
    fun disassociation(station: MacAddress, bssid: MacAddress, reasonCode: Int, sequenceNumber: Int = 0): ByteArray =
        managementFrame(SUBTYPE_DISASSOC, station, bssid, bssid, reasonBody(reasonCode), sequenceNumber)

    /**
     * The 24-octet MAC header of a from-DS data frame (data subtype 0): addr1 = [station] (DA),
     * addr2 = [bssid] (TA), addr3 = [source] (SA). With [protectedFrame] set the Protected Frame
     * bit is on, which is the header [com.mklhc.core.wifi_ap.crypto.Ccmp.encrypt] expects.
     */
    fun fromDsDataHeader(
        station: MacAddress,
        bssid: MacAddress,
        source: MacAddress,
        protectedFrame: Boolean = false,
        sequenceNumber: Int = 0,
    ): ByteArray = Ieee80211Frame(
        frameControl = FrameControl.of(TYPE_DATA, SUBTYPE_DATA, fromDs = true, protectedFrame = protectedFrame),
        addr1 = station,
        addr2 = bssid,
        addr3 = source,
        sequenceControl = SequenceControl.of(sequenceNumber),
    ).toBytes()

    /**
     * A complete from-DS data frame: [fromDsDataHeader] followed by [body] (normally an LLC/SNAP
     * encapsulated payload, see [LlcSnap]). Leave [protectedFrame] false for a clear frame; for a
     * CCMP frame encrypt the body with the header instead.
     */
    fun fromDsData(
        station: MacAddress,
        bssid: MacAddress,
        source: MacAddress,
        body: ByteArray,
        protectedFrame: Boolean = false,
        sequenceNumber: Int = 0,
    ): ByteArray = fromDsDataHeader(station, bssid, source, protectedFrame, sequenceNumber) + body

    private const val AUTH_BODY_FIXED_BYTES = 6
    private const val ASSOC_RESPONSE_FIXED_BYTES = 6
    private const val BEACON_BODY_FIXED_BYTES = 12

    private fun beaconBody(timestamp: Long, beaconIntervalTu: Int, capabilityInfo: Int, elements: ByteArray): ByteArray {
        val body = ByteWriter(BEACON_BODY_FIXED_BYTES + elements.size)
        body.le64(timestamp)
        body.le16(beaconIntervalTu)
        body.le16(capabilityInfo)
        body.bytes(elements)
        return body.toByteArray()
    }

    private fun reasonBody(reasonCode: Int): ByteArray = ByteWriter(2).also { it.le16(reasonCode) }.toByteArray()
}

/** Little-endian append-only byte buffer used to assemble frames. */
internal class ByteWriter(initialCapacity: Int = 64) {

    private var buffer = ByteArray(maxOf(initialCapacity, 1))
    private var size = 0

    fun u8(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun le16(value: Int) {
        u8(value and 0xFF)
        u8((value shr 8) and 0xFF)
    }

    fun le32(value: Int) {
        le16(value and 0xFFFF)
        le16((value ushr 16) and 0xFFFF)
    }

    fun le64(value: Long) {
        le32((value and 0xFFFFFFFFL).toInt())
        le32((value ushr 32).toInt())
    }

    fun bytes(source: ByteArray) {
        ensureCapacity(source.size)
        source.copyInto(buffer, destinationOffset = size)
        size += source.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(extra: Int) {
        if (size + extra > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }
}

/** Reads an unsigned little-endian 16-bit value at [offset]. */
internal fun ByteArray.le16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** Reads a little-endian 32-bit value at [offset] (as a signed Int holding all 32 bits). */
internal fun ByteArray.le32(offset: Int): Int = le16(offset) or (le16(offset + 2) shl 16)

/** Reads a little-endian 64-bit value at [offset]. */
internal fun ByteArray.le64(offset: Int): Long =
    (le32(offset).toLong() and 0xFFFFFFFFL) or (le32(offset + 4).toLong() shl 32)
