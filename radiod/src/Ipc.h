/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * Ipc.h - framed message codec for the radiod <-> app socket.
 *
 * This is the C++ twin of the Kotlin IpcCodec (core/radio, IpcCodec.kt) and MUST stay byte-identical
 * to it. Everything is little-endian. Every message is a fixed 8 byte header plus a payload:
 *
 *     u16 type | u16 flags | u32 length | payload[length]
 *
 * `flags` is reserved: written as 0, ignored on read. A variable-length field (string or byte array)
 * is `u32 byteCount + bytes`, including the last field of a payload; written lp(x) below.
 *
 *   app -> radiod                                   radiod -> app
 *   HELLO         0x0001 u16 version, lp(token)     READY     0x0101 lp(chipId), lp(firmware), lp(devourerVersion)
 *   OPEN_DEVICE   0x0002 u16 vid, u16 pid           RX_FRAME  0x0102 i16 rssi, u8 rate, u64 tsf, lp(frame)
 *   INIT          0x0003 u8 channel, u8 bwMhz,      STATUS    0x0103 u32 rx, u32 tx, u32 txErrors, u32 beacons
 *                        u8 txPowerIndex            LOG       0x0104 u8 level, lp(text)
 *   SET_IDENTITY  0x0004 mac[6] (no length prefix)  ERROR     0x0105 u32 code, lp(text)
 *   START_BEACON  0x0005 u16 intervalTu, lp(beacon)
 *   UPDATE_BEACON 0x0006 lp(beacon)
 *   STOP_BEACON   0x0007 (empty)
 *   TX_FRAME      0x0008 lp(rate), u8 retry, u8 flags, lp(frame)
 *   SHUTDOWN      0x0009 (empty)
 *   LIST_DEVICES  0x000A u32 requestId              DEVICE_LIST 0x0106 u32 requestId, u32 count, then
 *                                                     count x (u16 vid, u16 pid, u8 bus, u8 address,
 *                                                     u8 deviceClass, u8 access)
 *
 * Protocol version 2 added LIST_DEVICES / DEVICE_LIST. DEVICE_LIST echoes the requestId of the
 * LIST_DEVICES it answers, so the app never takes a late answer for a newer request. `access` is one of
 * the kUsbAccess* codes below; the app decodes a code it does not know as "unknown" rather than
 * rejecting the list.
 *
 * Decoding is strict, like the Kotlin side: short header, unknown type, declared length that does not
 * match the buffer, a field overrunning the payload, invalid UTF-8, a DEVICE_LIST count that does not
 * match the devices carried and unread payload bytes are all rejected with DecodeError. Encoding replaces
 * invalid UTF-8 in text fields with '?' so a stray byte in a devourer log line can never make the Kotlin
 * decoder reject a whole message.
 */

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace radiod::ipc {

using Bytes = std::vector<uint8_t>;

/** Size in bytes of the message header. */
inline constexpr size_t kHeaderSize = 8;

/** Protocol version carried in HELLO; must equal IpcCodec.PROTOCOL_VERSION on the Kotlin side. */
inline constexpr uint16_t kProtocolVersion = 2;

/** Largest payload accepted on encode and decode (1 MiB); protects against corrupt lengths. */
inline constexpr uint32_t kMaxPayloadLength = 1u << 20;

/** Size in bytes of a MAC address. */
inline constexpr size_t kMacSize = 6;

/** Message type ids, exactly as carried in the first two header bytes. */
enum class MessageType : uint16_t {
    Hello = 0x0001,
    OpenDevice = 0x0002,
    Init = 0x0003,
    SetIdentity = 0x0004,
    StartBeacon = 0x0005,
    UpdateBeacon = 0x0006,
    StopBeacon = 0x0007,
    TxFrame = 0x0008,
    Shutdown = 0x0009,
    ListDevices = 0x000A,
    Ready = 0x0101,
    RxFrame = 0x0102,
    Status = 0x0103,
    Log = 0x0104,
    Error = 0x0105,
    DeviceList = 0x0106,
};

/** LOG levels; the app treats any value above kLogWarn as an error. */
inline constexpr uint8_t kLogDebug = 0;
inline constexpr uint8_t kLogInfo = 1;
inline constexpr uint8_t kLogWarn = 2;
inline constexpr uint8_t kLogError = 3;

/**
 * DEVICE_LIST access codes: the result of a trial libusb_open + libusb_close of the device (no claim). Only
 * devices with a vendor-specific interface (class 0xFF) are opened; any other is reported NOT_PROBED.
 */
inline constexpr uint8_t kUsbAccessOpenable = 0;           // libusb_open succeeded
inline constexpr uint8_t kUsbAccessDriverUnsupported = 1;  // LIBUSB_ERROR_NOT_SUPPORTED (Windows: not WinUSB)
inline constexpr uint8_t kUsbAccessDenied = 2;             // LIBUSB_ERROR_ACCESS outside Windows (no permission,
                                                           // on Linux a missing udev rule)
inline constexpr uint8_t kUsbAccessBusy = 3;               // in use by another program: LIBUSB_ERROR_ACCESS on
                                                           // Windows (WinUSB grants one handle; our radio too),
                                                           // LIBUSB_ERROR_BUSY elsewhere (rare from libusb_open)
inline constexpr uint8_t kUsbAccessError = 4;              // any other libusb error
inline constexpr uint8_t kUsbAccessNotProbed = 5;          // not opened: does not look like a Wi-Fi adapter

/** Size in bytes of one DEVICE_LIST entry on the wire. */
inline constexpr size_t kUsbDeviceEntrySize = 8;

/** Most entries one DEVICE_LIST can carry: the payload minus its u32 requestId and u32 count. */
inline constexpr size_t kMaxDeviceListEntries = (kMaxPayloadLength - 8) / kUsbDeviceEntrySize;

/**
 * Codes carried by ERROR messages. The app does not interpret them (it logs code and text); they are
 * defined here so radiod's failures are greppable and stable. Values are radiod-defined.
 */
enum class ErrorCode : uint32_t {
    BadHello = 1,          // first message was not a valid HELLO (wrong type, version or token)
    MalformedMessage = 2,  // a message with a valid length could not be decoded; it was skipped
    ProtocolDesync = 3,    // corrupt length: the stream is out of sync, the connection is dropped
    UsbOpenFailed = 10,    // libusb init or device open failed (not plugged in, wrong driver bound)
    UsbClaimFailed = 11,   // interface claim / reset failed (LIBUSB_ERROR_BUSY = adapter in use)
    RadioCreateFailed = 12,  // devourer could not create a radio for this adapter
    RadioInitFailed = 13,  // devourer bring-up (InitWrite) threw
    RadioNotReady = 20,    // command needs OPEN_DEVICE / INIT first
    InvalidArgument = 21,  // a command carried a value radiod cannot use
    BeaconFailed = 30,     // StartBeacon / UpdateBeaconPayload / StopBeacon reported failure
    RxLoopFailed = 40,     // the RX loop threw or ended unexpectedly (adapter unplugged?)
    Internal = 99,         // unexpected exception inside radiod
};

// ---------------------------------------------------------------------------------------------
// app -> radiod
// ---------------------------------------------------------------------------------------------

struct Hello {
    uint16_t protocolVersion = 0;
    std::string token;
};

struct OpenDevice {
    uint16_t vid = 0;
    uint16_t pid = 0;
};

struct Init {
    uint8_t channel = 0;
    uint8_t bandwidthMhz = 0;
    uint8_t txPowerIndex = 0;
};

struct SetIdentity {
    std::array<uint8_t, kMacSize> mac{};
};

struct StartBeacon {
    uint16_t intervalTu = 0;
    Bytes beacon;
};

struct UpdateBeacon {
    Bytes beacon;
};

struct StopBeacon {};

struct TxFrame {
    std::string rate;
    uint8_t retryLimit = 0;
    uint8_t flags = 0;
    Bytes frame;
};

struct Shutdown {};

struct ListDevices {
    uint32_t requestId = 0;
};

// ---------------------------------------------------------------------------------------------
// radiod -> app
// ---------------------------------------------------------------------------------------------

struct Ready {
    std::string chipId;
    std::string firmware;
    std::string devourerVersion;
};

struct RxFrame {
    int16_t rssi = 0;
    uint8_t rate = 0;
    uint64_t tsf = 0;
    Bytes frame;
};

struct Status {
    uint32_t rx = 0;
    uint32_t tx = 0;
    uint32_t txErrors = 0;
    uint32_t beacons = 0;
};

struct Log {
    uint8_t level = kLogInfo;
    std::string text;
};

struct Error {
    uint32_t code = 0;
    std::string text;
};

/** One USB device in a DEVICE_LIST. */
struct UsbDeviceEntry {
    uint16_t vid = 0;
    uint16_t pid = 0;
    uint8_t bus = 0;
    uint8_t address = 0;
    uint8_t deviceClass = 0;
    uint8_t access = kUsbAccessError;
};

struct DeviceList {
    uint32_t requestId = 0;  // echoes the LIST_DEVICES this answers
    std::vector<UsbDeviceEntry> devices;
};

/** Any IPC message, in either direction. */
using Message = std::variant<Hello, OpenDevice, Init, SetIdentity, StartBeacon, UpdateBeacon, StopBeacon,
                             TxFrame, Shutdown, ListDevices, Ready, RxFrame, Status, Log, Error, DeviceList>;

/** Thrown by decode / payloadLengthOf when bytes do not form a valid IPC message. */
class DecodeError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

/** Returns the wire type id of [msg]. */
MessageType typeOf(const Message& msg);

/** Returns a short upper-case name such as "TX_FRAME" for logs. */
const char* nameOf(MessageType type);

/**
 * Encodes [msg] to header plus payload bytes.
 * Throws std::length_error when the payload would exceed kMaxPayloadLength.
 */
Bytes encode(const Message& msg);

/**
 * Decodes exactly one complete message (header plus payload) from [data].
 * Throws DecodeError when [data] is not a single well-formed message.
 */
Message decode(const uint8_t* data, size_t size);

/** Convenience overload of decode for a byte vector. */
inline Message decode(const Bytes& bytes) {
    return decode(bytes.data(), bytes.size());
}

/**
 * Returns the payload length declared by the header at the start of [header] (at least kHeaderSize
 * bytes). Stream readers call this after reading the header to learn how many bytes follow.
 * Throws DecodeError when the header is short or declares more than kMaxPayloadLength.
 */
uint32_t payloadLengthOf(const uint8_t* header, size_t size);

/** True when [text] is well-formed UTF-8 (no overlongs, no surrogates, max U+10FFFF). */
bool isValidUtf8(std::string_view text);

/** Returns [text] with every invalid UTF-8 byte replaced by '?'. */
std::string sanitizeUtf8(std::string_view text);

}  // namespace radiod::ipc
