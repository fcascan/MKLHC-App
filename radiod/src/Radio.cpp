/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * Radio.cpp - drives the USB Wi-Fi adapter through devourer. See Radio.h for the threading model.
 *
 * Business logic summary:
 *   OPEN_DEVICE  libusb_init, open by vid/pid, claim the Wi-Fi interface (with devourer's per-adapter
 *                lock), create the IRadio with a nonzero hardware retry limit and RX enabled during TX.
 *   INIT         (optional SetTxPower), InitWrite on the requested channel, start the TX thread, emit
 *                READY, start the RX thread running StartRxLoop.
 *   TX_FRAME     enqueue; the TX thread prepends the radiotap header and calls send_packet.
 *   RX           callback strips the FCS, drops corrupt frames and chip reports, emits RX_FRAME.
 *   shutdown     stop TX, stop the beacon (the chip keeps beaconing after the host dies), stop RX,
 *                power the chip down, then release the interface, handle and libusb context in the order
 *                devourer requires.
 */

#include "Radio.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <deque>
#include <exception>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#if __has_include(<libusb.h>)
#include <libusb.h>
#else
#include <libusb-1.0/libusb.h>
#endif

// devourer public headers; their include directories come from the devourer CMake target.
#include "DeviceConfig.h"
#include "IRadio.h"
#include "RadiotapBuilder.h"
#include "RxPacket.h"
#include "UsbDeviceLock.h"
#include "UsbOpen.h"
#include "WiFiDriver.h"
#include "logger.h"

#ifndef RADIOD_DEVOURER_VERSION
#define RADIOD_DEVOURER_VERSION "unknown"
#endif

namespace radiod {
namespace {

/** Frames waiting for the TX thread; beyond this the oldest-first policy is "drop the new frame". */
constexpr size_t kMaxTxQueue = 512;

/** Smallest frame radiod accepts: frame control + duration + address 1 (an ACK frame without FCS). */
constexpr size_t kMinFrameSize = 10;

/** Largest frame radiod accepts for TX (max MPDU is 2346 bytes; leave headroom for QoS/HT control). */
constexpr size_t kMaxFrameSize = 4096;

/** Smallest valid beacon: the 24 byte management header. */
constexpr size_t kMinBeaconSize = 24;

/** Trailing frame check sequence devourer may leave on received frames. */
constexpr size_t kFcsSize = 4;

/** Bound on distinct (rate, ack) radiotap headers kept; guards against garbage rate strings. */
constexpr size_t kMaxRadiotapCache = 64;

std::string usbErrorText(int rc) {
    const auto code = static_cast<libusb_error>(rc);
    return std::string(libusb_error_name(rc)) + " (" + libusb_strerror(code) + ")";
}

std::string hex16(uint16_t value) {
    char buffer[8];
    std::snprintf(buffer, sizeof(buffer), "%04x", static_cast<unsigned>(value));
    return buffer;
}

std::optional<ChannelWidth_t> widthFromMhz(uint8_t mhz) {
    switch (mhz) {
        case 5:
            return CHANNEL_WIDTH_5;
        case 10:
            return CHANNEL_WIDTH_10;
        case 20:
            return CHANNEL_WIDTH_20;
        case 40:
            return CHANNEL_WIDTH_40;
        case 80:
            return CHANNEL_WIDTH_80;
        case 160:
            return CHANNEL_WIDTH_160;
        default:
            return std::nullopt;
    }
}

/** True for chip-generated reports (C2H, TX status) that are not received 802.11 frames. */
bool isChipReport(RX_PACKET_TYPE type) {
    return type == RX_PACKET_TYPE::C2H_PACKET || type == RX_PACKET_TYPE::TX_REPORT1 ||
           type == RX_PACKET_TYPE::TX_REPORT2 || type == RX_PACKET_TYPE::HIS_REPORT;
}

/** True when address 1 (offset 4) has the group (multicast/broadcast) bit set. */
bool isGroupAddressed(const ipc::Bytes& frame) {
    return frame.size() >= 5 && (frame[4] & 0x01) != 0;
}

std::string describeFirmware(const devourer::FwBootStatus& fw) {
    if (!fw.supported) {
        return "n/a";
    }
    if (!fw.attempted) {
        return "not-downloaded";
    }
    if (fw.checksum_ok && fw.ready_ok) {
        return "booted";
    }
    return fw.checksum_ok ? "downloaded-not-ready" : "checksum-failed";
}

}  // namespace

// =============================================================================================
// Radio::Impl
// =============================================================================================

class Radio::Impl {
public:
    Impl(Emit emit, RadioOptions options)
        : emit_(std::move(emit)), options_(options), logger_(std::make_shared<Logger>()) {
        options_.retryLimit = std::clamp(options_.retryLimit, 1, 63);
        // devourer's Level enum is named through level() so this compiles whether it is nested or not.
        using Level = decltype(std::declval<const Logger&>().level());
        logger_->set_level(options_.verbose ? Level::Debug : Level::Info);
    }

    ~Impl() { shutdown(); }

    void redirectDiagnostics(std::FILE* stream) { logger_->set_diag_stream(stream); }

    Failure openDevice(uint16_t vid, uint16_t pid) {
        if (handle_ != nullptr) {
            return fail(ipc::ErrorCode::InvalidArgument, "OPEN_DEVICE: an adapter is already open", false);
        }

        int rc = libusb_init(&ctx_);
        if (rc != 0) {
            ctx_ = nullptr;
            return fail(ipc::ErrorCode::UsbOpenFailed, "libusb_init failed: " + usbErrorText(rc), true);
        }
        libusb_set_option(ctx_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

        handle_ = libusb_open_device_with_vid_pid(ctx_, vid, pid);
        if (handle_ == nullptr) {
            releaseUsb();
            return fail(ipc::ErrorCode::UsbOpenFailed,
                        "no USB device " + hex16(vid) + ":" + hex16(pid) +
                            " could be opened (not plugged in, or not bound to WinUSB/libusb)",
                        true);
        }

        try {
            iface_ = devourer::find_wifi_interface(handle_);
            rc = devourer::claim_interface_then_reset(handle_, iface_, logger_, true, lock_);
            if (rc != 0) {
                releaseUsb();
                const bool busy = rc == LIBUSB_ERROR_BUSY;
                return fail(ipc::ErrorCode::UsbClaimFailed,
                            std::string(busy ? "adapter is in use by another process: " : "claim/reset failed: ") +
                                usbErrorText(rc),
                            true);
            }

            devourer::DeviceConfig cfg;
            cfg.tx.retry_limit = options_.retryLimit;  // unicast hardware ARQ (Jaguar2/3)
            cfg.rx.enable_with_tx = true;              // Jaguar3: keep RX open during the TX bring-up

            driver_ = std::make_unique<WiFiDriver>(logger_);
            radio_ = driver_->CreateRadio(handle_, ctx_, lock_, cfg);
        } catch (const std::exception& e) {
            releaseUsb();
            return fail(ipc::ErrorCode::RadioCreateFailed, std::string("creating the radio threw: ") + e.what(), true);
        }
        if (!radio_) {
            releaseUsb();
            return fail(ipc::ErrorCode::RadioCreateFailed,
                        "devourer could not create a radio for " + hex16(vid) + ":" + hex16(pid) +
                            " (unsupported adapter, or already in use)",
                        true);
        }

        info("adapter opened: " + hex16(vid) + ":" + hex16(pid) + " interface=" + std::to_string(iface_) +
             " retryLimit=" + std::to_string(options_.retryLimit));
        return std::nullopt;
    }

    Failure init(uint8_t channel, uint8_t bandwidthMhz, uint8_t txPowerIndex) {
        if (!radio_) {
            return fail(ipc::ErrorCode::RadioNotReady, "INIT before OPEN_DEVICE", false);
        }
        const std::optional<ChannelWidth_t> width = widthFromMhz(bandwidthMhz);
        if (!width) {
            return fail(ipc::ErrorCode::InvalidArgument,
                        "INIT: unsupported bandwidth " + std::to_string(bandwidthMhz) + " MHz", false);
        }
        SelectedChannel selected{};
        selected.Channel = channel;
        selected.ChannelOffset = 0;
        selected.ChannelWidth = *width;

        if (ready_) {
            // A second INIT retunes the running radio.
            try {
                std::lock_guard<std::mutex> guard(deviceMutex_);
                radio_->SetMonitorChannel(selected);
                if (txPowerIndex != 0) {
                    radio_->SetTxPower(txPowerIndex);
                }
            } catch (const std::exception& e) {
                return fail(ipc::ErrorCode::RadioInitFailed, std::string("retune failed: ") + e.what(), false);
            }
            info("retuned: channel=" + std::to_string(channel) + " bandwidthMhz=" + std::to_string(bandwidthMhz));
            return std::nullopt;
        }

        try {
            if (txPowerIndex != 0) {
                radio_->SetTxPower(txPowerIndex);  // before InitWrite, as devourer's own tools do
            }
            radio_->InitWrite(selected);  // TX bring-up; RX is started separately on its own thread
        } catch (const std::exception& e) {
            return fail(ipc::ErrorCode::RadioInitFailed, std::string("bring-up failed: ") + e.what(), true);
        }

        ready_ = true;
        startTxThread();

        std::string chipId;
        std::string firmware;
        try {
            const auto caps = radio_->GetAdapterCaps();
            chipId = caps.chip_name != nullptr ? caps.chip_name : "";
            if (caps.variant != nullptr && caps.variant[0] != '\0') {
                chipId += std::string(" (") + caps.variant + ")";
            }
            firmware = describeFirmware(radio_->GetFwBootStatus());
        } catch (const std::exception& e) {
            warn(std::string("reading adapter caps failed: ") + e.what());
        }
        info("radio up: chip=" + chipId + " firmware=" + firmware + " channel=" + std::to_string(channel) +
             " bandwidthMhz=" + std::to_string(bandwidthMhz));

        applyIdentity();
        emit_(ipc::Ready{chipId, firmware, RADIOD_DEVOURER_VERSION});
        startRxThread();
        return std::nullopt;
    }

    Failure setIdentity(const std::array<uint8_t, ipc::kMacSize>& mac) {
        devourer::MacAddr address;
        address.bytes = mac;
        identity_ = address;
        if (ready_) {
            applyIdentity();
        }
        return std::nullopt;
    }

    Failure startBeacon(uint16_t intervalTu, const ipc::Bytes& beacon) {
        if (!ready_) {
            return fail(ipc::ErrorCode::RadioNotReady, "START_BEACON before INIT", false);
        }
        if (intervalTu == 0 || beacon.size() < kMinBeaconSize || beacon.size() > kMaxFrameSize) {
            return fail(ipc::ErrorCode::InvalidArgument,
                        "START_BEACON: intervalTu=" + std::to_string(intervalTu) +
                            " beaconBytes=" + std::to_string(beacon.size()),
                        false);
        }
        bool ok = false;
        try {
            std::lock_guard<std::mutex> guard(deviceMutex_);
            ok = radio_->StartBeacon(beacon.data(), beacon.size(), intervalTu);
        } catch (const std::exception& e) {
            return fail(ipc::ErrorCode::BeaconFailed, std::string("StartBeacon threw: ") + e.what(), false);
        }
        if (!ok) {
            return fail(ipc::ErrorCode::BeaconFailed, "StartBeacon reported failure", false);
        }
        beaconRunning_ = true;
        ++beacons_;
        info("beacon started: intervalTu=" + std::to_string(intervalTu) + " bytes=" + std::to_string(beacon.size()));
        return std::nullopt;
    }

    Failure updateBeacon(const ipc::Bytes& beacon) {
        if (!beaconRunning_) {
            return fail(ipc::ErrorCode::RadioNotReady, "UPDATE_BEACON without a running beacon", false);
        }
        if (beacon.size() < kMinBeaconSize || beacon.size() > kMaxFrameSize) {
            return fail(ipc::ErrorCode::InvalidArgument,
                        "UPDATE_BEACON: beaconBytes=" + std::to_string(beacon.size()), false);
        }
        bool ok = false;
        try {
            std::lock_guard<std::mutex> guard(deviceMutex_);
            ok = radio_->UpdateBeaconPayload(beacon.data(), beacon.size());
        } catch (const std::exception& e) {
            return fail(ipc::ErrorCode::BeaconFailed, std::string("UpdateBeaconPayload threw: ") + e.what(), false);
        }
        if (!ok) {
            return fail(ipc::ErrorCode::BeaconFailed, "UpdateBeaconPayload reported failure", false);
        }
        ++beacons_;
        return std::nullopt;
    }

    Failure stopBeacon() {
        if (!beaconRunning_) {
            return std::nullopt;
        }
        bool ok = false;
        try {
            std::lock_guard<std::mutex> guard(deviceMutex_);
            ok = radio_->StopBeacon();
        } catch (const std::exception& e) {
            return fail(ipc::ErrorCode::BeaconFailed, std::string("StopBeacon threw: ") + e.what(), false);
        }
        if (!ok) {
            // Keep beaconRunning_ set so shutdown() tries again; the chip beacons until told to stop.
            return fail(ipc::ErrorCode::BeaconFailed, "StopBeacon reported failure", false);
        }
        beaconRunning_ = false;
        info("beacon stopped");
        return std::nullopt;
    }

    Failure txFrame(const std::string& rate, uint8_t retryLimit, uint8_t flags, ipc::Bytes frame) {
        if (!ready_) {
            return fail(ipc::ErrorCode::RadioNotReady, "TX_FRAME before INIT", false);
        }
        if (frame.size() < kMinFrameSize || frame.size() > kMaxFrameSize) {
            return fail(ipc::ErrorCode::InvalidArgument, "TX_FRAME: frameBytes=" + std::to_string(frame.size()), false);
        }
        if (retryLimit != 0 && retryLimit != options_.retryLimit && !warnedPerFrameRetry_.exchange(true)) {
            // devourer takes the retry limit once, in DeviceConfig; there is no per-frame control.
            warn("per-frame retry limit is not applied; using the device-wide limit " +
                 std::to_string(options_.retryLimit) + " (set with --retry-limit)");
        }

        bool dropped = false;
        {
            std::lock_guard<std::mutex> guard(txMutex_);
            if (txQueue_.size() >= kMaxTxQueue) {
                dropped = true;
            } else {
                txQueue_.push_back(TxJob{rate, flags, std::move(frame)});
            }
        }
        if (dropped) {
            noteTxError("TX queue full, frame dropped");
        } else {
            txCv_.notify_one();
        }
        return std::nullopt;
    }

    void shutdown() {
        if (shutdownDone_.exchange(true)) {
            return;
        }
        shuttingDown_ = true;

        // 1. Stop transmitting; whatever is still queued is dropped.
        {
            std::lock_guard<std::mutex> guard(txMutex_);
            txStop_ = true;
        }
        txCv_.notify_all();
        if (txThread_.joinable()) {
            txThread_.join();
        }

        // 2. The chip keeps beaconing after the host process exits, so stop it explicitly.
        if (radio_ && beaconRunning_) {
            try {
                std::lock_guard<std::mutex> guard(deviceMutex_);
                if (!radio_->StopBeacon()) {
                    warn("StopBeacon reported failure during shutdown; power-cycle the adapter to silence it");
                }
            } catch (const std::exception& e) {
                warn(std::string("StopBeacon threw during shutdown: ") + e.what());
            }
            beaconRunning_ = false;
        }

        // 3. Stop receiving.
        if (radio_ && rxThread_.joinable()) {
            try {
                radio_->StopRxLoop();
            } catch (const std::exception& e) {
                warn(std::string("StopRxLoop threw: ") + e.what());
            }
            rxThread_.join();
        }

        // 4. Power the chip down, then release USB in devourer's required order:
        //    device, interface, handle, context, lock.
        if (radio_ && ready_) {
            try {
                radio_->Stop();
            } catch (const std::exception& e) {
                warn(std::string("Stop threw: ") + e.what());
            }
        }
        ready_ = false;
        radio_.reset();
        driver_.reset();
        releaseUsb();
    }

    bool ready() const { return ready_; }

    bool hasFatalError() const { return fatal_; }

    ipc::Status counters() const {
        ipc::Status status;
        status.rx = rx_;
        status.tx = tx_;
        status.txErrors = txErrors_;
        status.beacons = beacons_;
        return status;
    }

private:
    struct TxJob {
        std::string rate;
        uint8_t flags = 0;
        ipc::Bytes frame;
    };

    // ----- logging and errors ------------------------------------------------------------------

    void log(uint8_t level, std::string text) { emit_(ipc::Log{level, std::move(text)}); }
    void info(std::string text) { log(ipc::kLogInfo, std::move(text)); }
    void warn(std::string text) { log(ipc::kLogWarn, std::move(text)); }

    RadioError fail(ipc::ErrorCode code, std::string text, bool fatal) {
        if (fatal) {
            fatal_ = true;
        }
        return RadioError{code, std::move(text), fatal};
    }

    /** Reports an error from a radiod thread (no command to return it to) and marks it fatal. */
    void reportFatalAsync(ipc::ErrorCode code, std::string text) {
        fatal_ = true;
        emit_(ipc::Error{static_cast<uint32_t>(code), std::move(text)});
    }

    /** Counts a TX failure and logs the first one and then every 100th to avoid flooding the app. */
    void noteTxError(const std::string& reason) {
        const uint32_t count = ++txErrors_;
        if (count == 1 || count % 100 == 0) {
            warn("TX failure #" + std::to_string(count) + ": " + reason);
        }
    }

    // ----- identity ----------------------------------------------------------------------------

    void applyIdentity() {
        if (!identity_ || !radio_) {
            return;
        }
        try {
            std::lock_guard<std::mutex> guard(deviceMutex_);
            if (!radio_->SetAckResponder(*identity_)) {
                warn("SetAckResponder reported failure; relying on the beacon BSSID for hardware ACKs");
            }
        } catch (const std::exception& e) {
            warn(std::string("SetAckResponder threw: ") + e.what());
        }
    }

    // ----- RX ----------------------------------------------------------------------------------

    void startRxThread() {
        rxThread_ = std::thread([this] {
            try {
                radio_->StartRxLoop([this](const Packet& packet) { onPacket(packet); });
            } catch (const std::exception& e) {
                if (!shuttingDown_) {
                    reportFatalAsync(ipc::ErrorCode::RxLoopFailed, std::string("RX loop failed: ") + e.what());
                }
                return;
            }
            if (!shuttingDown_) {
                reportFatalAsync(ipc::ErrorCode::RxLoopFailed, "RX loop ended unexpectedly");
            }
        });
    }

    /** RX callback; runs on the RX thread and must not call back into the device. */
    void onPacket(const Packet& packet) {
        const auto& attrib = packet.RxAtrib;
        if (isChipReport(attrib.pkt_rpt_type) || attrib.crc_err) {
            return;
        }
        size_t length = packet.Data.size();
        if (attrib.fcs_present) {
            if (length <= kFcsSize) {
                return;
            }
            length -= kFcsSize;
        }
        if (length < kMinFrameSize) {
            return;
        }

        ipc::RxFrame message;
        // rssi[] is a uint8_t array; reading it as two's complement keeps both a dBm and a percent
        // reading correct. Which one devourer reports is confirmed in the hardware spike.
        message.rssi = static_cast<int16_t>(static_cast<int8_t>(attrib.rssi[0]));
        message.rate = static_cast<uint8_t>(attrib.data_rate & 0xFF);
        message.tsf = attrib.tsfl;  // devourer exposes only the low 32 bits of the TSF
        message.frame.assign(packet.Data.data(), packet.Data.data() + length);
        emit_(message);
        ++rx_;
    }

    // ----- TX ----------------------------------------------------------------------------------

    void startTxThread() {
        txThread_ = std::thread([this] { txLoop(); });
    }

    void txLoop() {
        for (;;) {
            TxJob job;
            {
                std::unique_lock<std::mutex> lock(txMutex_);
                txCv_.wait(lock, [this] { return txStop_ || !txQueue_.empty(); });
                if (txStop_) {
                    return;
                }
                job = std::move(txQueue_.front());
                txQueue_.pop_front();
            }
            sendJob(job);
        }
    }

    void sendJob(const TxJob& job) {
        try {
            const bool noAck = (job.flags & kTxFlagNoAck) != 0 || isGroupAddressed(job.frame);
            const std::vector<uint8_t>& radiotap = radiotapFor(job.rate, noAck);

            std::vector<uint8_t> buffer;
            buffer.reserve(radiotap.size() + job.frame.size());
            buffer.insert(buffer.end(), radiotap.begin(), radiotap.end());
            buffer.insert(buffer.end(), job.frame.begin(), job.frame.end());

            bool ok = false;
            {
                std::lock_guard<std::mutex> guard(deviceMutex_);
                ok = radio_->send_packet(buffer.data(), buffer.size());
            }
            if (ok) {
                ++tx_;
            } else {
                noteTxError("send_packet returned false");
            }
        } catch (const std::exception& e) {
            noteTxError(std::string("send_packet threw: ") + e.what());
        }
    }

    /** Radiotap header for (rate, ack); cached because parse_tx_mode_str may print warnings. TX thread only. */
    const std::vector<uint8_t>& radiotapFor(const std::string& rate, bool noAck) {
        std::string key = rate.empty() ? std::string("6M") : rate;
        key += noAck ? "|noack" : "|ack";
        auto found = radiotapCache_.find(key);
        if (found == radiotapCache_.end()) {
            if (radiotapCache_.size() >= kMaxRadiotapCache) {
                radiotapCache_.clear();
            }
            const devourer::TxMode mode = devourer::parse_tx_mode_str(rate.empty() ? std::string("6M") : rate);
            found = radiotapCache_.emplace(key, devourer::build_stream_radiotap(mode, noAck)).first;
        }
        return found->second;
    }

    // ----- USB teardown ------------------------------------------------------------------------

    /** Releases interface, handle, context and lock; safe to call with any subset still open. */
    void releaseUsb() {
        if (handle_ != nullptr) {
            const int rc = libusb_release_interface(handle_, iface_);
            if (rc != 0) {
                info("libusb_release_interface: " + usbErrorText(rc));  // not fatal, as in devourer's tools
            }
            libusb_close(handle_);
            handle_ = nullptr;
        }
        if (ctx_ != nullptr) {
            libusb_exit(ctx_);
            ctx_ = nullptr;
        }
        lock_.reset();
    }

    // ----- state -------------------------------------------------------------------------------

    Emit emit_;
    RadioOptions options_;
    Logger_t logger_;

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    int iface_ = 0;
    std::shared_ptr<devourer::UsbDeviceLock> lock_;
    std::unique_ptr<WiFiDriver> driver_;
    std::unique_ptr<IRadio> radio_;

    /** Serializes every call into IRadio that is not the RX loop: send_packet, beacon, identity, retune. */
    std::mutex deviceMutex_;

    std::optional<devourer::MacAddr> identity_;

    std::thread rxThread_;
    std::thread txThread_;
    std::mutex txMutex_;
    std::condition_variable txCv_;
    std::deque<TxJob> txQueue_;
    bool txStop_ = false;
    std::unordered_map<std::string, std::vector<uint8_t>> radiotapCache_;

    std::atomic<bool> ready_{false};
    std::atomic<bool> fatal_{false};
    std::atomic<bool> beaconRunning_{false};
    std::atomic<bool> shuttingDown_{false};
    std::atomic<bool> shutdownDone_{false};
    std::atomic<bool> warnedPerFrameRetry_{false};

    std::atomic<uint32_t> rx_{0};
    std::atomic<uint32_t> tx_{0};
    std::atomic<uint32_t> txErrors_{0};
    std::atomic<uint32_t> beacons_{0};
};

// =============================================================================================
// Radio (pimpl forwarding)
// =============================================================================================

Radio::Radio(Emit emit, RadioOptions options) : impl_(std::make_unique<Impl>(std::move(emit), options)) {}

Radio::~Radio() = default;

void Radio::redirectDiagnostics(std::FILE* stream) {
    impl_->redirectDiagnostics(stream);
}

Failure Radio::openDevice(uint16_t vid, uint16_t pid) {
    return impl_->openDevice(vid, pid);
}

Failure Radio::init(uint8_t channel, uint8_t bandwidthMhz, uint8_t txPowerIndex) {
    return impl_->init(channel, bandwidthMhz, txPowerIndex);
}

Failure Radio::setIdentity(const std::array<uint8_t, ipc::kMacSize>& mac) {
    return impl_->setIdentity(mac);
}

Failure Radio::startBeacon(uint16_t intervalTu, const ipc::Bytes& beacon) {
    return impl_->startBeacon(intervalTu, beacon);
}

Failure Radio::updateBeacon(const ipc::Bytes& beacon) {
    return impl_->updateBeacon(beacon);
}

Failure Radio::stopBeacon() {
    return impl_->stopBeacon();
}

Failure Radio::txFrame(const std::string& rate, uint8_t retryLimit, uint8_t flags, ipc::Bytes frame) {
    return impl_->txFrame(rate, retryLimit, flags, std::move(frame));
}

void Radio::shutdown() {
    impl_->shutdown();
}

bool Radio::ready() const {
    return impl_->ready();
}

bool Radio::hasFatalError() const {
    return impl_->hasFatalError();
}

ipc::Status Radio::counters() const {
    return impl_->counters();
}

}  // namespace radiod
