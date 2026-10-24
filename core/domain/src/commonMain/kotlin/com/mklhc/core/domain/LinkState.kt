package com.mklhc.core.domain

/**
 * Which of the two Wi-Fi networks the access point is advertising.
 *
 * Business logic: the app serves one network at a time. [PAIRING] is the short-lived network a kart
 * joins after scanning the pairing QR code (it is then told the game network's credentials); [GAME]
 * is the installation's permanent network that a paired kart joins on its own. The orchestrator
 * switches the beacon between them without tearing the radio down.
 */
enum class KartNetworkKind {
    /** The network used only while a kart is being paired. */
    PAIRING,

    /** The installation's permanent network a paired kart joins to be driven. */
    GAME,
}

/**
 * Where the link between the app and the kart currently stands.
 *
 * Business logic: bringing a kart under control is a strictly ordered ladder; each rung needs the one
 * before it, and the orchestrator publishes the rung it has reached so the UI can show progress:
 *
 * ```
 * NoAdapter -> AwaitingUsbPermission -> RadioStarting -> Beaconing(network)
 *   -> KartAssociated -> KartLeased -> KartHandshaked -> KartReady -> Driving
 * ```
 *
 * - [NoAdapter]: no USB radio adapter is attached.
 * - [AwaitingUsbPermission]: an adapter is attached but the user has not yet granted access to it.
 * - [RadioStarting]: access is granted and the radio is being brought up.
 * - [Beaconing]: the access point is advertising [KartNetworkKind] and waiting for the kart to join.
 * - [KartAssociated]: the kart has joined the Wi-Fi network (802.11 association).
 * - [KartLeased]: the kart has obtained its IP address from the app's DHCP server.
 * - [KartHandshaked]: the kart's control connection is up and its handshake has completed.
 * - [KartReady]: the kart has been identified and put in the state in which it accepts driving.
 * - [Driving]: the drive command stream is being sent. (The drive command itself,
 *   `DriveCommand`, currently lives in `:core:kart-protocol`, package `teleop`, and is reused as is
 *   rather than duplicated here.)
 *
 * Two states sit off the ladder: [Recovering] when an established link was lost and the orchestrator
 * is climbing back up on its own (also shown while the radio itself is brought back up underneath the
 * session, after an adapter replug), and [Failed] when it gave up and needs the user (or a restart) to
 * continue. Both carry a human-readable text for the UI and the logs; it is never a secret.
 */
sealed interface LinkState {

    /** No USB radio adapter is attached. */
    data object NoAdapter : LinkState

    /** An adapter is attached but the user has not granted access to it yet. */
    data object AwaitingUsbPermission : LinkState

    /** The radio is being brought up. */
    data object RadioStarting : LinkState

    /**
     * The access point is advertising [network] and no kart has joined yet.
     *
     * @property network which network is being advertised.
     */
    data class Beaconing(val network: KartNetworkKind) : LinkState

    /** The kart has joined the Wi-Fi network. */
    data object KartAssociated : LinkState

    /** The kart has been given its IP address. */
    data object KartLeased : LinkState

    /** The kart's control connection is up and its handshake has completed. */
    data object KartHandshaked : LinkState

    /** The kart is identified and ready to be driven. */
    data object KartReady : LinkState

    /** The drive command stream is being sent to the kart. */
    data object Driving : LinkState

    /**
     * An established link was lost and the orchestrator is recovering it automatically; also shown while the radio is
     * brought back up underneath the session (an unplugged adapter), before anything is on the air again.
     *
     * @property reason why the link was lost, or what the radio is waiting for, for display and logs.
     */
    data class Recovering(val reason: String) : LinkState

    /**
     * The orchestrator could not continue; user action or a restart is needed.
     *
     * @property error what went wrong, for display and logs.
     */
    data class Failed(val error: String) : LinkState
}
