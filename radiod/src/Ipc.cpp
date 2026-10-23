/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * Ipc.cpp - implementation of the framed message codec declared in Ipc.h. Pure C++ standard library,
 * no platform APIs, little-endian regardless of the host byte order.
 */

#include "Ipc.h"

#include <algorithm>
#include <string>
#include <type_traits>
#include <utility>

namespace radiod::ipc {
namespace {

// ---------------------------------------------------------------------------------------------
// Message type <-> C++ type mapping
// ---------------------------------------------------------------------------------------------

template <typename T>
struct TypeOf;

template <>
struct TypeOf<Hello> {
    static constexpr MessageType value = MessageType::Hello;
};
template <>
struct TypeOf<OpenDevice> {
    static constexpr MessageType value = MessageType::OpenDevice;
};
template <>
struct TypeOf<Init> {
    static constexpr MessageType value = MessageType::Init;
};
template <>
struct TypeOf<SetIdentity> {
    static constexpr MessageType value = MessageType::SetIdentity;
};
template <>
struct TypeOf<StartBeacon> {
    static constexpr MessageType value = MessageType::StartBeacon;
};
template <>
struct TypeOf<UpdateBeacon> {
    static constexpr MessageType value = MessageType::UpdateBeacon;
};
template <>
struct TypeOf<StopBeacon> {
    static constexpr MessageType value = MessageType::StopBeacon;
};
template <>
struct TypeOf<TxFrame> {
    static constexpr MessageType value = MessageType::TxFrame;
};
template <>
struct TypeOf<Shutdown> {
    static constexpr MessageType value = MessageType::Shutdown;
};
template <>
struct TypeOf<ListDevices> {
    static constexpr MessageType value = MessageType::ListDevices;
};
template <>
struct TypeOf<Ready> {
    static constexpr MessageType value = MessageType::Ready;
};
template <>
struct TypeOf<RxFrame> {
    static constexpr MessageType value = MessageType::RxFrame;
};
template <>
struct TypeOf<Status> {
    static constexpr MessageType value = MessageType::Status;
};
template <>
struct TypeOf<Log> {
    static constexpr MessageType value = MessageType::Log;
};
template <>
struct TypeOf<Error> {
    static constexpr MessageType value = MessageType::Error;
};
template <>
struct TypeOf<DeviceList> {
    static constexpr MessageType value = MessageType::DeviceList;
};

bool isKnownType(uint16_t id) {
    switch (static_cast<MessageType>(id)) {
        case MessageType::Hello:
        case MessageType::OpenDevice:
        case MessageType::Init:
        case MessageType::SetIdentity:
        case MessageType::StartBeacon:
        case MessageType::UpdateBeacon:
        case MessageType::StopBeacon:
        case MessageType::TxFrame:
        case MessageType::Shutdown:
        case MessageType::ListDevices:
        case MessageType::Ready:
        case MessageType::RxFrame:
        case MessageType::Status:
        case MessageType::Log:
        case MessageType::Error:
        case MessageType::DeviceList:
            return true;
    }
    return false;
}

// ---------------------------------------------------------------------------------------------
// UTF-8
// ---------------------------------------------------------------------------------------------

bool isContinuation(uint8_t b) {
    return (b & 0xC0) == 0x80;
}

/** Length in bytes of the well-formed UTF-8 sequence at [p], or 0 when the sequence is invalid. */
size_t utf8SequenceLength(const uint8_t* p, size_t remaining) {
    const uint8_t b0 = p[0];
    if (b0 < 0x80) {
        return 1;
    }
    if (b0 >= 0xC2 && b0 <= 0xDF) {
        return (remaining >= 2 && isContinuation(p[1])) ? 2 : 0;
    }
    if (b0 >= 0xE0 && b0 <= 0xEF) {
        if (remaining < 3) {
            return 0;
        }
        const uint8_t b1 = p[1];
        uint8_t lo = 0x80;
        uint8_t hi = 0xBF;
        if (b0 == 0xE0) {
            lo = 0xA0;  // no overlong 3 byte forms
        } else if (b0 == 0xED) {
            hi = 0x9F;  // no UTF-16 surrogates
        }
        return (b1 >= lo && b1 <= hi && isContinuation(p[2])) ? 3 : 0;
    }
    if (b0 >= 0xF0 && b0 <= 0xF4) {
        if (remaining < 4) {
            return 0;
        }
        const uint8_t b1 = p[1];
        uint8_t lo = 0x80;
        uint8_t hi = 0xBF;
        if (b0 == 0xF0) {
            lo = 0x90;  // no overlong 4 byte forms
        } else if (b0 == 0xF4) {
            hi = 0x8F;  // nothing above U+10FFFF
        }
        return (b1 >= lo && b1 <= hi && isContinuation(p[2]) && isContinuation(p[3])) ? 4 : 0;
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// Little-endian writer and reader
// ---------------------------------------------------------------------------------------------

/** Growable little-endian writer that reserves the header in front of the payload. */
class Writer {
public:
    Writer() : buffer_(kHeaderSize, 0) {}

    void u8(uint8_t value) { buffer_.push_back(value); }

    void u16(uint16_t value) {
        u8(static_cast<uint8_t>(value & 0xFF));
        u8(static_cast<uint8_t>((value >> 8) & 0xFF));
    }

    void u32(uint32_t value) {
        for (int i = 0; i < 4; ++i) {
            u8(static_cast<uint8_t>((value >> (8 * i)) & 0xFF));
        }
    }

    void u64(uint64_t value) {
        for (int i = 0; i < 8; ++i) {
            u8(static_cast<uint8_t>((value >> (8 * i)) & 0xFF));
        }
    }

    void raw(const uint8_t* data, size_t size) { buffer_.insert(buffer_.end(), data, data + size); }

    void lengthPrefixed(const uint8_t* data, size_t size) {
        if (size > kMaxPayloadLength) {
            throw std::length_error("field too large: length=" + std::to_string(size));
        }
        u32(static_cast<uint32_t>(size));
        raw(data, size);
    }

    void lengthPrefixed(const Bytes& bytes) { lengthPrefixed(bytes.data(), bytes.size()); }

    /** Writes [text] as lp(utf8), replacing invalid UTF-8 so the app can always decode it. */
    void string(std::string_view text) {
        if (isValidUtf8(text)) {
            lengthPrefixed(reinterpret_cast<const uint8_t*>(text.data()), text.size());
        } else {
            const std::string clean = sanitizeUtf8(text);
            lengthPrefixed(reinterpret_cast<const uint8_t*>(clean.data()), clean.size());
        }
    }

    /** Fills in the header for [type] and returns the finished message. */
    Bytes finish(MessageType type) {
        const size_t payloadLength = buffer_.size() - kHeaderSize;
        if (payloadLength > kMaxPayloadLength) {
            throw std::length_error("payload too large: type=" + std::string(nameOf(type)) +
                                    " length=" + std::to_string(payloadLength));
        }
        const uint16_t typeId = static_cast<uint16_t>(type);
        buffer_[0] = static_cast<uint8_t>(typeId & 0xFF);
        buffer_[1] = static_cast<uint8_t>((typeId >> 8) & 0xFF);
        // bytes 2..3: reserved flags, left as 0
        for (int i = 0; i < 4; ++i) {
            buffer_[4 + i] = static_cast<uint8_t>((payloadLength >> (8 * i)) & 0xFF);
        }
        return std::move(buffer_);
    }

private:
    Bytes buffer_;
};

/** Bounds-checked little-endian reader over one message's payload. */
class Reader {
public:
    Reader(const uint8_t* data, size_t size) : position_(data), end_(data + size) {}

    uint8_t u8() {
        need(1);
        return *position_++;
    }

    uint16_t u16() {
        need(2);
        const uint16_t value = static_cast<uint16_t>(position_[0] | (position_[1] << 8));
        position_ += 2;
        return value;
    }

    int16_t i16() { return static_cast<int16_t>(u16()); }

    uint32_t u32() {
        need(4);
        uint32_t value = 0;
        for (int i = 0; i < 4; ++i) {
            value |= static_cast<uint32_t>(position_[i]) << (8 * i);
        }
        position_ += 4;
        return value;
    }

    uint64_t u64() {
        need(8);
        uint64_t value = 0;
        for (int i = 0; i < 8; ++i) {
            value |= static_cast<uint64_t>(position_[i]) << (8 * i);
        }
        position_ += 8;
        return value;
    }

    Bytes raw(size_t count) {
        need(count);
        Bytes out(position_, position_ + count);
        position_ += count;
        return out;
    }

    Bytes lengthPrefixed() {
        const uint32_t count = u32();
        if (count > remaining()) {
            throw DecodeError("field length overruns payload: length=" + std::to_string(count) +
                              " remaining=" + std::to_string(remaining()));
        }
        return raw(count);
    }

    std::string string() {
        const Bytes bytes = lengthPrefixed();
        std::string text(bytes.begin(), bytes.end());
        if (!isValidUtf8(text)) {
            throw DecodeError("invalid UTF-8 text: length=" + std::to_string(text.size()));
        }
        return text;
    }

    void requireFullyConsumed(MessageType type) const {
        if (position_ != end_) {
            throw DecodeError("unread payload bytes: type=" + std::string(nameOf(type)) +
                              " unread=" + std::to_string(remaining()));
        }
    }

    /** Payload bytes not read yet. */
    size_t remaining() const { return static_cast<size_t>(end_ - position_); }

private:
    void need(size_t count) const {
        if (remaining() < count) {
            throw DecodeError("payload too short: need=" + std::to_string(count) +
                              " remaining=" + std::to_string(remaining()));
        }
    }

    const uint8_t* position_;
    const uint8_t* end_;
};

// ---------------------------------------------------------------------------------------------
// Payload writers, one overload per message
// ---------------------------------------------------------------------------------------------

void writePayload(Writer& w, const Hello& m) {
    w.u16(m.protocolVersion);
    w.string(m.token);
}

void writePayload(Writer& w, const OpenDevice& m) {
    w.u16(m.vid);
    w.u16(m.pid);
}

void writePayload(Writer& w, const Init& m) {
    w.u8(m.channel);
    w.u8(m.bandwidthMhz);
    w.u8(m.txPowerIndex);
}

void writePayload(Writer& w, const SetIdentity& m) {
    w.raw(m.mac.data(), m.mac.size());  // plain 6 bytes, no length prefix
}

void writePayload(Writer& w, const StartBeacon& m) {
    w.u16(m.intervalTu);
    w.lengthPrefixed(m.beacon);
}

void writePayload(Writer& w, const UpdateBeacon& m) {
    w.lengthPrefixed(m.beacon);
}

void writePayload(Writer&, const StopBeacon&) {}

void writePayload(Writer& w, const TxFrame& m) {
    w.string(m.rate);
    w.u8(m.retryLimit);
    w.u8(m.flags);
    w.lengthPrefixed(m.frame);
}

void writePayload(Writer&, const Shutdown&) {}

void writePayload(Writer& w, const ListDevices& m) {
    w.u32(m.requestId);
}

void writePayload(Writer& w, const Ready& m) {
    w.string(m.chipId);
    w.string(m.firmware);
    w.string(m.devourerVersion);
}

void writePayload(Writer& w, const RxFrame& m) {
    w.u16(static_cast<uint16_t>(m.rssi));  // i16, two's complement
    w.u8(m.rate);
    w.u64(m.tsf);
    w.lengthPrefixed(m.frame);
}

void writePayload(Writer& w, const Status& m) {
    w.u32(m.rx);
    w.u32(m.tx);
    w.u32(m.txErrors);
    w.u32(m.beacons);
}

void writePayload(Writer& w, const Log& m) {
    w.u8(m.level);
    w.string(m.text);
}

void writePayload(Writer& w, const Error& m) {
    w.u32(m.code);
    w.string(m.text);
}

void writePayload(Writer& w, const DeviceList& m) {
    if (m.devices.size() > kMaxDeviceListEntries) {
        throw std::length_error("too many devices: count=" + std::to_string(m.devices.size()) +
                                " max=" + std::to_string(kMaxDeviceListEntries));
    }
    w.u32(m.requestId);
    w.u32(static_cast<uint32_t>(m.devices.size()));
    for (const UsbDeviceEntry& device : m.devices) {
        w.u16(device.vid);
        w.u16(device.pid);
        w.u8(device.bus);
        w.u8(device.address);
        w.u8(device.deviceClass);
        w.u8(device.access);
    }
}

// ---------------------------------------------------------------------------------------------
// Payload reader
// ---------------------------------------------------------------------------------------------

Message readPayload(MessageType type, Reader& r) {
    switch (type) {
        case MessageType::Hello: {
            Hello m;
            m.protocolVersion = r.u16();
            m.token = r.string();
            return m;
        }
        case MessageType::OpenDevice: {
            OpenDevice m;
            m.vid = r.u16();
            m.pid = r.u16();
            return m;
        }
        case MessageType::Init: {
            Init m;
            m.channel = r.u8();
            m.bandwidthMhz = r.u8();
            m.txPowerIndex = r.u8();
            return m;
        }
        case MessageType::SetIdentity: {
            SetIdentity m;
            const Bytes mac = r.raw(kMacSize);
            std::copy(mac.begin(), mac.end(), m.mac.begin());
            return m;
        }
        case MessageType::StartBeacon: {
            StartBeacon m;
            m.intervalTu = r.u16();
            m.beacon = r.lengthPrefixed();
            return m;
        }
        case MessageType::UpdateBeacon: {
            UpdateBeacon m;
            m.beacon = r.lengthPrefixed();
            return m;
        }
        case MessageType::StopBeacon:
            return StopBeacon{};
        case MessageType::TxFrame: {
            TxFrame m;
            m.rate = r.string();
            m.retryLimit = r.u8();
            m.flags = r.u8();
            m.frame = r.lengthPrefixed();
            return m;
        }
        case MessageType::Shutdown:
            return Shutdown{};
        case MessageType::ListDevices: {
            ListDevices m;
            m.requestId = r.u32();
            return m;
        }
        case MessageType::Ready: {
            Ready m;
            m.chipId = r.string();
            m.firmware = r.string();
            m.devourerVersion = r.string();
            return m;
        }
        case MessageType::RxFrame: {
            RxFrame m;
            m.rssi = r.i16();
            m.rate = r.u8();
            m.tsf = r.u64();
            m.frame = r.lengthPrefixed();
            return m;
        }
        case MessageType::Status: {
            Status m;
            m.rx = r.u32();
            m.tx = r.u32();
            m.txErrors = r.u32();
            m.beacons = r.u32();
            return m;
        }
        case MessageType::Log: {
            Log m;
            m.level = r.u8();
            m.text = r.string();
            return m;
        }
        case MessageType::Error: {
            Error m;
            m.code = r.u32();
            m.text = r.string();
            return m;
        }
        case MessageType::DeviceList: {
            DeviceList m;
            m.requestId = r.u32();
            const uint32_t count = r.u32();
            if (count > r.remaining() / kUsbDeviceEntrySize) {
                // Checked before allocating, so a corrupt count cannot reserve gigabytes.
                throw DecodeError("device count overruns payload: count=" + std::to_string(count) +
                                  " remaining=" + std::to_string(r.remaining()));
            }
            m.devices.reserve(count);
            for (uint32_t i = 0; i < count; ++i) {
                UsbDeviceEntry device;
                device.vid = r.u16();
                device.pid = r.u16();
                device.bus = r.u8();
                device.address = r.u8();
                device.deviceClass = r.u8();
                device.access = r.u8();
                m.devices.push_back(device);
            }
            return m;
        }
    }
    throw DecodeError("unknown message type");  // unreachable: callers check isKnownType first
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------------------------

MessageType typeOf(const Message& msg) {
    return std::visit([](const auto& m) { return TypeOf<std::decay_t<decltype(m)>>::value; }, msg);
}

const char* nameOf(MessageType type) {
    switch (type) {
        case MessageType::Hello:
            return "HELLO";
        case MessageType::OpenDevice:
            return "OPEN_DEVICE";
        case MessageType::Init:
            return "INIT";
        case MessageType::SetIdentity:
            return "SET_IDENTITY";
        case MessageType::StartBeacon:
            return "START_BEACON";
        case MessageType::UpdateBeacon:
            return "UPDATE_BEACON";
        case MessageType::StopBeacon:
            return "STOP_BEACON";
        case MessageType::TxFrame:
            return "TX_FRAME";
        case MessageType::Shutdown:
            return "SHUTDOWN";
        case MessageType::ListDevices:
            return "LIST_DEVICES";
        case MessageType::Ready:
            return "READY";
        case MessageType::RxFrame:
            return "RX_FRAME";
        case MessageType::Status:
            return "STATUS";
        case MessageType::Log:
            return "LOG";
        case MessageType::Error:
            return "ERROR";
        case MessageType::DeviceList:
            return "DEVICE_LIST";
    }
    return "UNKNOWN";
}

Bytes encode(const Message& msg) {
    return std::visit(
        [](const auto& m) {
            Writer writer;
            writePayload(writer, m);
            return writer.finish(TypeOf<std::decay_t<decltype(m)>>::value);
        },
        msg);
}

uint32_t payloadLengthOf(const uint8_t* header, size_t size) {
    if (size < kHeaderSize) {
        throw DecodeError("truncated header: size=" + std::to_string(size));
    }
    uint32_t length = 0;
    for (int i = 0; i < 4; ++i) {
        length |= static_cast<uint32_t>(header[4 + i]) << (8 * i);
    }
    if (length > kMaxPayloadLength) {
        throw DecodeError("declared length above maximum: length=" + std::to_string(length) +
                          " max=" + std::to_string(kMaxPayloadLength));
    }
    return length;
}

Message decode(const uint8_t* data, size_t size) {
    if (size < kHeaderSize) {
        throw DecodeError("truncated header: size=" + std::to_string(size));
    }
    const uint16_t typeId = static_cast<uint16_t>(data[0] | (data[1] << 8));
    if (!isKnownType(typeId)) {
        throw DecodeError("unknown message type: id=" + std::to_string(typeId));
    }
    const MessageType type = static_cast<MessageType>(typeId);
    const uint32_t length = payloadLengthOf(data, size);
    const size_t available = size - kHeaderSize;
    if (length > available) {
        throw DecodeError("declared length exceeds buffer: type=" + std::string(nameOf(type)) +
                          " length=" + std::to_string(length) + " available=" + std::to_string(available));
    }
    if (length < available) {
        throw DecodeError("bytes after declared payload: type=" + std::string(nameOf(type)) +
                          " length=" + std::to_string(length) + " available=" + std::to_string(available));
    }

    Reader reader(data + kHeaderSize, length);
    Message message = readPayload(type, reader);
    reader.requireFullyConsumed(type);
    return message;
}

bool isValidUtf8(std::string_view text) {
    const auto* p = reinterpret_cast<const uint8_t*>(text.data());
    size_t index = 0;
    while (index < text.size()) {
        const size_t length = utf8SequenceLength(p + index, text.size() - index);
        if (length == 0) {
            return false;
        }
        index += length;
    }
    return true;
}

std::string sanitizeUtf8(std::string_view text) {
    const auto* p = reinterpret_cast<const uint8_t*>(text.data());
    std::string out;
    out.reserve(text.size());
    size_t index = 0;
    while (index < text.size()) {
        const size_t length = utf8SequenceLength(p + index, text.size() - index);
        if (length == 0) {
            out.push_back('?');
            ++index;
        } else {
            out.append(text.data() + index, length);
            index += length;
        }
    }
    return out;
}

}  // namespace radiod::ipc
