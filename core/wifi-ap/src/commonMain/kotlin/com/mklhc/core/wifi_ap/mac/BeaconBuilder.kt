package com.mklhc.core.wifi_ap.mac

/*
 * Implemented from the IEEE 802.11 standard ("IEEE 802.11 frame formats": beacon and probe
 * response frame bodies, information element ids and ordering) and the BSS parameters in the
 * project design (section 8.1). Original code; no third-party source copied.
 */

/**
 * Builds the beacon and probe response of the hidden LP2P access point.
 *
 * Business logic: the console (kart) finds the network by its LP2P vendor elements and its SSID,
 * and joins it like a normal WPA-less, statically keyed 802.11g network. The beacon therefore:
 *
 * - is a broadcast management frame from the [bssid] (addr2 = addr3 = BSSID);
 * - advertises capability ESS + Privacy (static CCMP, so the Privacy bit is set) and carries NO RSN
 *   element, mirroring OpenKart's hostapd patch;
 * - hides the SSID the way hostapd's `ignore_broadcast_ssid=2` does: the SSID element keeps the
 *   real length but every byte is zero, so scanners see a hidden network while a station that
 *   already knows the SSID can still ask for it with a directed probe request;
 * - lists its elements in this order: SSID, Supported Rates, DS Parameter Set, TIM, ERP, Extended
 *   Supported Rates, then the two LP2P vendor elements ([vendorElements], appended verbatim).
 *
 * The probe response mirrors the beacon but carries the REAL SSID and has no TIM (a TIM is only
 * defined for beacons). The timestamp field is 0 in the beacon because the radio hardware
 * overwrites it with its TSF when it transmits the beacon.
 *
 * Hardware-spike tunables (all constructor parameters or documented constants): the beacon
 * interval, the exact rate set ([SUPPORTED_RATES] / [EXTENDED_SUPPORTED_RATES], the 802.11g set
 * hostapd advertises for `hw_mode=g`), [capabilityInfo] (short-preamble / short-slot bits are
 * deliberately not set), [dtimPeriod] and [erpInfo].
 *
 * @property bssid the locally administered unicast BSSID.
 * @property ssid the real network name (UTF-8, 1..32 octets).
 * @property channel the 2.4 GHz channel announced in the DS Parameter Set (1..14).
 * @property beaconIntervalTu the beacon interval in time units of 1024 microseconds.
 * @property vendorElements the two concatenated LP2P vendor elements, appended last.
 */
class BeaconBuilder(
    val bssid: MacAddress,
    val ssid: String,
    val channel: Int,
    val beaconIntervalTu: Int,
    val vendorElements: ByteArray,
    val capabilityInfo: Int = CAPABILITY_ESS or CAPABILITY_PRIVACY,
    val dtimPeriod: Int = 1,
    val erpInfo: Int = 0,
) {

    private val ssidBytes: ByteArray = ssid.encodeToByteArray()

    init {
        require(!bssid.isMulticast) { "BSSID must be a unicast address: $bssid" }
        require(ssidBytes.size in 1..MAX_SSID_BYTES) { "SSID must be 1..$MAX_SSID_BYTES octets, got ${ssidBytes.size}" }
        require(channel in 1..MAX_CHANNEL) { "channel must be 1..$MAX_CHANNEL: $channel" }
        require(beaconIntervalTu in 1..MAX_BEACON_INTERVAL_TU) { "beacon interval must be 1..$MAX_BEACON_INTERVAL_TU TU" }
    }

    /**
     * The beacon MPDU (no FCS): hidden SSID, timestamp 0 and the element order described on the
     * class. [sequenceNumber] is normally 0; the hardware repeats the frame verbatim.
     */
    fun beacon(sequenceNumber: Int = 0): ByteArray {
        val hiddenSsid = InformationElements.build(InformationElements.SSID, ByteArray(ssidBytes.size))
        val elements = hiddenSsid +
            InformationElements.supportedRates(SUPPORTED_RATES) +
            InformationElements.dsParameterSet(channel) +
            InformationElements.tim(dtimCount = 0, dtimPeriod = dtimPeriod, bitmapControl = 0) +
            InformationElements.erp(erpInfo) +
            InformationElements.extendedSupportedRates(EXTENDED_SUPPORTED_RATES) +
            vendorElements
        return Ieee80211.beacon(bssid, timestamp = 0L, beaconIntervalTu, capabilityInfo, elements, sequenceNumber)
    }

    /**
     * The probe response to [station]: the beacon's elements with the real SSID and without the
     * TIM. [timestamp] defaults to 0; pass the radio's TSF when it is known.
     */
    fun probeResponse(station: MacAddress, timestamp: Long = 0L, sequenceNumber: Int = 0): ByteArray {
        val elements = InformationElements.ssid(ssidBytes) +
            InformationElements.supportedRates(SUPPORTED_RATES) +
            InformationElements.dsParameterSet(channel) +
            InformationElements.erp(erpInfo) +
            InformationElements.extendedSupportedRates(EXTENDED_SUPPORTED_RATES) +
            vendorElements
        return Ieee80211.probeResponse(station, bssid, timestamp, beaconIntervalTu, capabilityInfo, elements, sequenceNumber)
    }

    /** Supported Rates followed by Extended Supported Rates, the rate elements an association response carries. */
    fun rateElements(): ByteArray =
        InformationElements.supportedRates(SUPPORTED_RATES) +
            InformationElements.extendedSupportedRates(EXTENDED_SUPPORTED_RATES)

    companion object {
        /** Capability bit: the BSS is an infrastructure (ESS) network. */
        const val CAPABILITY_ESS = 0x0001

        /** Capability bit: the BSS requires encryption (static CCMP, no RSN element). */
        const val CAPABILITY_PRIVACY = 0x0010

        private const val MAX_SSID_BYTES = 32
        private const val MAX_CHANNEL = 14
        private const val MAX_BEACON_INTERVAL_TU = 0xFFFF

        /**
         * Supported Rates: 1, 2, 5.5 and 11 Mbit/s as basic rates (bit 7 set: 0x82, 0x84, 0x8B, 0x96)
         * then 6, 9, 12 and 18 Mbit/s (0x0C, 0x12, 0x18, 0x24). Units are 500 kbit/s. The exact set is
         * a hardware-spike tunable.
         */
        private val SUPPORTED_RATES = byteArrayOf(
            0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(),
            0x0C, 0x12, 0x18, 0x24,
        )

        /** Extended Supported Rates: 24, 36, 48 and 54 Mbit/s (0x30, 0x48, 0x60, 0x6C). Spike tunable. */
        private val EXTENDED_SUPPORTED_RATES = byteArrayOf(0x30, 0x48, 0x60, 0x6C)
    }
}
