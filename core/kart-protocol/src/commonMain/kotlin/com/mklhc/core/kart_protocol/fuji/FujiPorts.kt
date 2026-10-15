// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.fuji

/**
 * The TCP/UDP ports the kart ("Fuji") listens on, as defined by the reference daemon.
 *
 * Business logic: after the RCD handshake the app is a client of the kart's control (5103) and event
 * (5107) ports, must connect to both within a few seconds or the kart resets, and uses the pairing port
 * (5106) only while the kart is in pairing mode. Teleoperation datagrams go to the drive port (5102).
 */
object FujiPorts {
    /** UDP port the kart receives teleoperation (drive) datagrams on. */
    const val DRIVE = 5102

    /** TCP port of the Fuji control service ([FujiControlClient]). */
    const val CONTROL = 5103

    /** TCP port of the Fuji pairing service ([FujiPairingClient]); pairing mode only. */
    const val PAIRING = 5106

    /** TCP port of the event channel ([FujiEventChannel]). */
    const val EVENT = 5107
}
