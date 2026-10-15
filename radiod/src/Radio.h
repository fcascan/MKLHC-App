/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * Radio.h - the radio half of radiod: owns the USB adapter and the devourer IRadio.
 *
 * Business logic: the IPC layer (main.cpp) decodes app commands and calls the methods below on its
 * own thread. Radio hides every devourer and libusb type behind a pimpl, so this header (and main.cpp)
 * compile without devourer. Three threads run while the radio is up:
 *
 *   - IPC thread (the caller): open / init / identity / beacon commands, each guarded by a device mutex;
 *   - RX thread: runs IRadio::StartRxLoop; its callback turns every good frame into an RX_FRAME message;
 *   - TX thread: drains a bounded queue and calls IRadio::send_packet. devourer requires send_packet to
 *     come from a different thread than the RX callback (it returns BUSY otherwise), which is why
 *     TX_FRAME only enqueues and never transmits on the IPC thread.
 *
 * Every method that can fail returns a Failure; std::nullopt means success. A `fatal` failure means
 * the session cannot continue and radiod should report the ERROR and exit so the supervisor restarts it.
 */

#pragma once

#include <array>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <optional>
#include <string>

#include "Ipc.h"

namespace radiod {

/** TX_FRAME flag bit 0: transmit without expecting an ACK (no hardware retries). */
inline constexpr uint8_t kTxFlagNoAck = 0x01;

/** Why a command failed; sent to the app as an ERROR message. */
struct RadioError {
    ipc::ErrorCode code = ipc::ErrorCode::Internal;
    std::string text;
    bool fatal = false;
};

/** std::nullopt on success, the failure otherwise. */
using Failure = std::optional<RadioError>;

/** Settings fixed at startup from the command line. */
struct RadioOptions {
    /** Device-wide hardware retry limit for unicast frames (devourer cfg.tx.retry_limit, 1..63). */
    int retryLimit = 7;
    /** Pass devourer debug logging through; off keeps devourer at info level. */
    bool verbose = false;
};

class Radio {
public:
    /** Sink for every radiod -> app message; may be called from any radiod thread. */
    using Emit = std::function<void(const ipc::Message&)>;

    Radio(Emit emit, RadioOptions options);
    ~Radio();

    Radio(const Radio&) = delete;
    Radio& operator=(const Radio&) = delete;

    /**
     * Routes devourer's diagnostic output to [stream] (devourer logs to a FILE*, not a callback).
     * main.cpp passes the write end of a pipe and turns each line into a LOG message.
     */
    void redirectDiagnostics(std::FILE* stream);

    /** OPEN_DEVICE: opens the adapter by USB vendor/product id and creates the devourer radio. */
    Failure openDevice(uint16_t vid, uint16_t pid);

    /**
     * INIT: brings the radio up for TX and RX on [channel] with [bandwidthMhz] and starts the RX and
     * TX threads, then emits READY. A txPowerIndex of 0 keeps the chip default. Calling it again on a
     * running radio retunes the channel instead.
     */
    Failure init(uint8_t channel, uint8_t bandwidthMhz, uint8_t txPowerIndex);

    /** SET_IDENTITY: own MAC for the hardware auto-ACK engine. Best effort; stored until INIT if early. */
    Failure setIdentity(const std::array<uint8_t, ipc::kMacSize>& mac);

    /** START_BEACON: loads the full beacon frame and starts hardware beaconing every [intervalTu] TU. */
    Failure startBeacon(uint16_t intervalTu, const ipc::Bytes& beacon);

    /** UPDATE_BEACON: swaps the content of the running beacon. */
    Failure updateBeacon(const ipc::Bytes& beacon);

    /** STOP_BEACON: stops hardware beaconing; succeeds if no beacon is running. */
    Failure stopBeacon();

    /**
     * TX_FRAME: queues one raw 802.11 MPDU for transmission (radiod prepends the radiotap header).
     * [rate] is a devourer rate string such as "6M" or "MCS3"; empty means "6M". Unicast frames are
     * sent expecting an ACK (hardware retries); group-addressed frames and frames with kTxFlagNoAck
     * are sent without. A full queue drops the frame and counts it as a TX error, it does not fail.
     */
    Failure txFrame(const std::string& rate, uint8_t retryLimit, uint8_t flags, ipc::Bytes frame);

    /** Stops TX, the beacon and RX, powers the chip down and releases USB. Idempotent. */
    void shutdown();

    /** True once INIT succeeded and until shutdown. */
    bool ready() const;

    /** True when an unrecoverable error happened (radiod should exit). */
    bool hasFatalError() const;

    /** Current counters for STATUS. */
    ipc::Status counters() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace radiod
