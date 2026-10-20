/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * main.cpp - process entry point and IPC loop.
 *
 * Business logic: the Kotlin app is the socket SERVER. It binds an AF_UNIX socket, spawns
 *
 *     radiod --socket <path> --token <token> [--retry-limit N] [--verbose] [extra...]
 *
 * and radiod CONNECTS to <path>. The app speaks first: its DesktopRadioLink sends HELLO
 * (protocol version + token) as soon as radiod connects, and radiod verifies both against its own
 * protocol version and --token before it accepts any command. Then radiod dispatches commands to
 * Radio and streams RX_FRAME, STATUS (about once a second), LOG and ERROR back. devourer's own log
 * output is captured through a pipe and forwarded as LOG messages.
 *
 * Exit codes: 0 clean (SHUTDOWN, app closed the socket, SIGINT/SIGTERM), 1 usage, 2 connect or
 * handshake failure, 3 fatal radio error (an ERROR was sent first), 4 unexpected exception.
 */

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <afunix.h>  // AF_UNIX / SOCKADDR_UN, Windows 10 1803+; must follow winsock2.h
#include <fcntl.h>
#include <io.h>
#else
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#endif

#include "Ipc.h"
#include "Radio.h"

#ifndef RADIOD_DEVOURER_VERSION
#define RADIOD_DEVOURER_VERSION "unknown"
#endif

namespace {

using radiod::Radio;
using radiod::RadioOptions;
namespace ipc = radiod::ipc;

constexpr int kExitOk = 0;
constexpr int kExitUsage = 1;
constexpr int kExitConnect = 2;
constexpr int kExitRadio = 3;
constexpr int kExitInternal = 4;

constexpr int kConnectAttempts = 10;
constexpr int kConnectRetryMs = 200;
constexpr int kHelloTimeoutMs = 5000;
constexpr int kTickMs = 200;
constexpr int kCloseDrainMs = 1000;
constexpr auto kStatusInterval = std::chrono::seconds(1);

volatile std::sig_atomic_t g_stopRequested = 0;

void onSignal(int) {
    g_stopRequested = 1;
}

// =============================================================================================
// Platform sockets (AF_UNIX on both Windows and POSIX)
// =============================================================================================

#if defined(_WIN32)
using Socket = SOCKET;
constexpr Socket kInvalidSocket = INVALID_SOCKET;
using UnixAddress = SOCKADDR_UN;

/** Starts Winsock for the process lifetime. */
class NetworkRuntime {
public:
    NetworkRuntime() {
        WSADATA data;
        ok_ = WSAStartup(MAKEWORD(2, 2), &data) == 0;
    }
    ~NetworkRuntime() {
        if (ok_) {
            WSACleanup();
        }
    }
    NetworkRuntime(const NetworkRuntime&) = delete;
    NetworkRuntime& operator=(const NetworkRuntime&) = delete;
    bool ok() const { return ok_; }

private:
    bool ok_ = false;
};

void closeSocket(Socket s) {
    closesocket(s);
}
#else
using Socket = int;
constexpr Socket kInvalidSocket = -1;
using UnixAddress = sockaddr_un;

class NetworkRuntime {
public:
    NetworkRuntime() { std::signal(SIGPIPE, SIG_IGN); }
    bool ok() const { return true; }
};

void closeSocket(Socket s) {
    ::close(s);
}
#endif

/** Connects to the AF_UNIX stream socket at [path]; kInvalidSocket on failure. */
Socket connectUnix(const std::string& path) {
    UnixAddress address;
    std::memset(&address, 0, sizeof(address));
    if (path.empty() || path.size() >= sizeof(address.sun_path)) {
        return kInvalidSocket;
    }
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);

    const Socket s = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == kInvalidSocket) {
        return kInvalidSocket;
    }
    if (::connect(s, reinterpret_cast<const sockaddr*>(&address), static_cast<int>(sizeof(address))) != 0) {
        closeSocket(s);
        return kInvalidSocket;
    }
    return s;
}

/** Waits up to [timeoutMs] for [s] to become readable: 1 readable (or EOF), 0 timeout, -1 error. */
int waitReadable(Socket s, int timeoutMs) {
    fd_set readSet;
    FD_ZERO(&readSet);
    FD_SET(s, &readSet);
    timeval timeout;
    timeout.tv_sec = timeoutMs / 1000;
    timeout.tv_usec = (timeoutMs % 1000) * 1000;
#if defined(_WIN32)
    const int rc = ::select(0, &readSet, nullptr, nullptr, &timeout);
#else
    const int rc = ::select(s + 1, &readSet, nullptr, nullptr, &timeout);
    if (rc < 0 && errno == EINTR) {
        return 0;
    }
#endif
    return rc > 0 ? 1 : (rc == 0 ? 0 : -1);
}

/** Writes all [size] bytes; false on any error or when the peer closed. */
bool sendAll(Socket s, const uint8_t* data, size_t size) {
    size_t sent = 0;
    while (sent < size) {
#if defined(_WIN32)
        const int n = ::send(s, reinterpret_cast<const char*>(data + sent), static_cast<int>(size - sent), 0);
#elif defined(MSG_NOSIGNAL)
        const ssize_t n = ::send(s, data + sent, size - sent, MSG_NOSIGNAL);
#else
        const ssize_t n = ::send(s, data + sent, size - sent, 0);
#endif
        if (n <= 0) {
#if !defined(_WIN32)
            if (n < 0 && errno == EINTR) {
                continue;
            }
#endif
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

enum class RecvStatus { Ok, Eof, Error };

/** Reads exactly [size] bytes. A clean EOF before the first byte is reported as Eof only if [eofAllowed]. */
RecvStatus recvAll(Socket s, uint8_t* data, size_t size, bool eofAllowed) {
    size_t received = 0;
    while (received < size) {
#if defined(_WIN32)
        const int n = ::recv(s, reinterpret_cast<char*>(data + received), static_cast<int>(size - received), 0);
#else
        const ssize_t n = ::recv(s, data + received, size - received, 0);
#endif
        if (n == 0) {
            return (received == 0 && eofAllowed) ? RecvStatus::Eof : RecvStatus::Error;
        }
        if (n < 0) {
#if !defined(_WIN32)
            if (errno == EINTR) {
                continue;
            }
#endif
            return RecvStatus::Error;
        }
        received += static_cast<size_t>(n);
    }
    return RecvStatus::Ok;
}

/**
 * Closes [s] so the app reads everything radiod sent before the connection goes away.
 *
 * A socket closed while it still holds unread incoming bytes (for example the INIT the app queues right behind an
 * OPEN_DEVICE that fails fatally) is closed abortively: Windows answers with a reset, and the app's stack can drop the
 * final ERROR before the app reads it. So radiod half-closes its sending side (the app then reads the last messages
 * and an EOF), discards whatever the app still sends until the app closes too or [timeoutMs] passes, and only then
 * closes the socket.
 */
void closeSocketGracefully(Socket s, int timeoutMs) {
#if defined(_WIN32)
    ::shutdown(s, SD_SEND);
#else
    ::shutdown(s, SHUT_WR);
#endif
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    uint8_t discard[512];
    for (;;) {
        const auto left =
            std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (left <= 0 || waitReadable(s, static_cast<int>(left)) <= 0) {
            break;  // the app did not close in time, or the socket failed
        }
#if defined(_WIN32)
        const int n = ::recv(s, reinterpret_cast<char*>(discard), static_cast<int>(sizeof(discard)), 0);
#else
        const ssize_t n = ::recv(s, discard, sizeof(discard), 0);
        if (n < 0 && errno == EINTR) {
            continue;
        }
#endif
        if (n <= 0) {
            break;  // EOF: the app closed its side too (or the connection is already gone)
        }
    }
    closeSocket(s);
}

// =============================================================================================
// Outgoing messages and local logging
// =============================================================================================

/** Thread-safe writer of radiod -> app messages; any thread may call send(). */
class Sender {
public:
    explicit Sender(Socket s) : socket_(s) {}

    void send(const ipc::Message& message) {
        ipc::Bytes bytes;
        try {
            bytes = ipc::encode(message);
        } catch (const std::exception& e) {
            std::fprintf(stderr, "radiod: dropping message %s: %s\n", ipc::nameOf(ipc::typeOf(message)), e.what());
            return;
        }
        std::lock_guard<std::mutex> guard(mutex_);
        if (lost_) {
            return;
        }
        if (!sendAll(socket_, bytes.data(), bytes.size())) {
            lost_ = true;
        }
    }

    /** True once a write failed: the app is gone and radiod should shut down. */
    bool lost() const { return lost_; }

private:
    Socket socket_;
    std::mutex mutex_;
    std::atomic<bool> lost_{false};
};

std::atomic<Sender*> g_sender{nullptr};

/** Logs to the app as a LOG message once connected, to stderr before that. */
void logLocal(uint8_t level, const std::string& text) {
    if (Sender* sender = g_sender.load()) {
        sender->send(ipc::Log{level, text});
    } else {
        std::fprintf(stderr, "radiod: %s\n", text.c_str());
    }
}

void sendError(Sender& sender, ipc::ErrorCode code, const std::string& text) {
    sender.send(ipc::Error{static_cast<uint32_t>(code), text});
}

/**
 * Captures devourer's diagnostic output. devourer's Logger writes lines of the form
 * "devourer [X] message" to a FILE*; this gives it the write end of a pipe and a thread that
 * turns each line into a LOG message (X: T/D debug, I info, W warn, E error).
 */
class LogPump {
public:
    LogPump() {
        int fds[2] = {-1, -1};
#if defined(_WIN32)
        if (_pipe(fds, 64 * 1024, _O_BINARY) != 0) {
            return;
        }
        writer_ = _fdopen(fds[1], "wb");
        if (writer_ == nullptr) {
            _close(fds[0]);
            _close(fds[1]);
            return;
        }
#else
        if (::pipe(fds) != 0) {
            return;
        }
        writer_ = ::fdopen(fds[1], "w");
        if (writer_ == nullptr) {
            ::close(fds[0]);
            ::close(fds[1]);
            return;
        }
#endif
        readFd_ = fds[0];
    }

    ~LogPump() { stop(); }

    LogPump(const LogPump&) = delete;
    LogPump& operator=(const LogPump&) = delete;

    bool ok() const { return writer_ != nullptr; }
    std::FILE* stream() const { return writer_; }

    void start() {
        if (ok() && !thread_.joinable()) {
            thread_ = std::thread([this] { run(); });
        }
    }

    /** Closes the write end (the reader sees EOF and exits) and joins. Nothing may log to stream() after this. */
    void stop() {
        if (writer_ != nullptr) {
            std::fclose(writer_);
            writer_ = nullptr;
        }
        if (thread_.joinable()) {
            thread_.join();
        }
        if (readFd_ >= 0) {
#if defined(_WIN32)
            _close(readFd_);
#else
            ::close(readFd_);
#endif
            readFd_ = -1;
        }
    }

private:
    void run() {
        std::string line;
        char chunk[1024];
        for (;;) {
#if defined(_WIN32)
            const int n = _read(readFd_, chunk, sizeof(chunk));
#else
            const ssize_t n = ::read(readFd_, chunk, sizeof(chunk));
#endif
            if (n <= 0) {
                break;
            }
            for (int i = 0; i < n; ++i) {
                if (chunk[i] == '\n') {
                    emitLine(line);
                    line.clear();
                } else {
                    line.push_back(chunk[i]);
                    if (line.size() >= 4096) {
                        emitLine(line);
                        line.clear();
                    }
                }
            }
        }
        if (!line.empty()) {
            emitLine(line);
        }
    }

    static void emitLine(std::string line) {
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) {
            line.pop_back();
        }
        if (line.empty()) {
            return;
        }
        uint8_t level = ipc::kLogInfo;
        // "devourer [X] message"
        if (line.size() >= 12 && line.compare(0, 10, "devourer [") == 0 && line[11] == ']') {
            switch (line[10]) {
                case 'T':
                case 'D':
                    level = ipc::kLogDebug;
                    break;
                case 'W':
                    level = ipc::kLogWarn;
                    break;
                case 'E':
                    level = ipc::kLogError;
                    break;
                default:
                    level = ipc::kLogInfo;
                    break;
            }
            line.erase(0, line.size() > 12 && line[12] == ' ' ? 13 : 12);
        }
        logLocal(level, line);
    }

    std::FILE* writer_ = nullptr;
    int readFd_ = -1;
    std::thread thread_;
};

// =============================================================================================
// Command line
// =============================================================================================

struct Args {
    std::string socketPath;
    std::string token;
    int retryLimit = 7;
    bool verbose = false;
    bool help = false;
    bool version = false;
    std::vector<std::string> ignored;
};

/** Parses argv; returns an empty string on success or a usage error message. */
std::string parseArgs(int argc, char** argv, Args& args) {
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        std::string value;
        bool hasInlineValue = false;
        std::string name = arg;
        const size_t equals = arg.find('=');
        if (arg.rfind("--", 0) == 0 && equals != std::string::npos) {
            name = arg.substr(0, equals);
            value = arg.substr(equals + 1);
            hasInlineValue = true;
        }

        auto takeValue = [&](std::string& out) -> bool {
            if (hasInlineValue) {
                out = value;
                return true;
            }
            if (i + 1 >= argc) {
                return false;
            }
            out = argv[++i];
            return true;
        };

        if (name == "--socket") {
            if (!takeValue(args.socketPath)) {
                return "--socket needs a path";
            }
        } else if (name == "--token") {
            if (!takeValue(args.token)) {
                return "--token needs a value";
            }
        } else if (name == "--retry-limit") {
            std::string text;
            if (!takeValue(text)) {
                return "--retry-limit needs a number";
            }
            char* end = nullptr;
            const long parsed = std::strtol(text.c_str(), &end, 10);
            if (end == text.c_str() || *end != '\0' || parsed < 1 || parsed > 63) {
                return "--retry-limit must be 1..63";
            }
            args.retryLimit = static_cast<int>(parsed);
        } else if (name == "--verbose") {
            args.verbose = true;
        } else if (name == "--help" || name == "-h") {
            args.help = true;
        } else if (name == "--version") {
            args.version = true;
        } else {
            args.ignored.push_back(arg);  // forwards-compatible: unknown extras are logged and skipped
        }
    }
    if (args.help || args.version) {
        return "";
    }
    if (args.socketPath.empty()) {
        return "--socket <path> is required";
    }
    if (args.token.empty()) {
        return "--token <token> is required";
    }
    return "";
}

void printUsage() {
    std::fprintf(stderr,
                 "usage: radiod --socket <path> --token <token> [--retry-limit N] [--verbose]\n"
                 "  radiod connects to the AF_UNIX socket at <path>, expects HELLO(version %u, token) from the\n"
                 "  app, then serves radio commands. It is spawned by the MKLHC app, not run by hand.\n",
                 static_cast<unsigned>(ipc::kProtocolVersion));
}

// =============================================================================================
// IPC session
// =============================================================================================

enum class ReadResult { Ok, Eof, IoError, Desync, Malformed };

/** Reads one framed message. On Desync/Malformed [why] describes the problem. */
ReadResult readMessage(Socket s, ipc::Message& out, std::string& why) {
    uint8_t header[ipc::kHeaderSize];
    const RecvStatus headerStatus = recvAll(s, header, sizeof(header), /*eofAllowed=*/true);
    if (headerStatus == RecvStatus::Eof) {
        return ReadResult::Eof;
    }
    if (headerStatus == RecvStatus::Error) {
        return ReadResult::IoError;
    }

    uint32_t payloadLength = 0;
    try {
        payloadLength = ipc::payloadLengthOf(header, sizeof(header));
    } catch (const ipc::DecodeError& e) {
        why = e.what();
        return ReadResult::Desync;  // corrupt length: the stream cannot be resynchronized
    }

    ipc::Bytes message(ipc::kHeaderSize + payloadLength);
    std::memcpy(message.data(), header, sizeof(header));
    if (payloadLength > 0 &&
        recvAll(s, message.data() + ipc::kHeaderSize, payloadLength, /*eofAllowed=*/false) != RecvStatus::Ok) {
        return ReadResult::IoError;
    }
    try {
        out = ipc::decode(message);
    } catch (const ipc::DecodeError& e) {
        why = e.what();
        return ReadResult::Malformed;  // valid length, bad content: skip it, the stream is still in sync
    }
    return ReadResult::Ok;
}

/** The app speaks first: wait for HELLO and check the protocol version and token. */
bool performHandshake(Socket s, Sender& sender, const std::string& expectedToken) {
    if (waitReadable(s, kHelloTimeoutMs) <= 0) {
        std::fprintf(stderr, "radiod: no HELLO within %d ms\n", kHelloTimeoutMs);
        return false;
    }
    ipc::Message first;
    std::string why;
    if (readMessage(s, first, why) != ReadResult::Ok) {
        std::fprintf(stderr, "radiod: could not read HELLO: %s\n", why.c_str());
        return false;
    }
    const auto* hello = std::get_if<ipc::Hello>(&first);
    if (hello == nullptr) {
        sendError(sender, ipc::ErrorCode::BadHello,
                  std::string("expected HELLO, got ") + ipc::nameOf(ipc::typeOf(first)));
        return false;
    }
    if (hello->protocolVersion != ipc::kProtocolVersion) {
        sendError(sender, ipc::ErrorCode::BadHello,
                  "protocol version mismatch: app=" + std::to_string(hello->protocolVersion) +
                      " radiod=" + std::to_string(ipc::kProtocolVersion));
        return false;
    }
    if (hello->token != expectedToken) {
        sendError(sender, ipc::ErrorCode::BadHello, "session token mismatch");
        return false;
    }
    return true;
}

/** Applies one command to [radio]; reports a failure to the app. Returns false when the session should end. */
bool dispatch(const ipc::Message& message, Radio& radio, Sender& sender) {
    auto report = [&](const radiod::Failure& failure) {
        if (failure) {
            sendError(sender, failure->code, failure->text);
        }
    };

    if (const auto* open = std::get_if<ipc::OpenDevice>(&message)) {
        report(radio.openDevice(open->vid, open->pid));
    } else if (const auto* init = std::get_if<ipc::Init>(&message)) {
        report(radio.init(init->channel, init->bandwidthMhz, init->txPowerIndex));
    } else if (const auto* identity = std::get_if<ipc::SetIdentity>(&message)) {
        report(radio.setIdentity(identity->mac));
    } else if (const auto* start = std::get_if<ipc::StartBeacon>(&message)) {
        report(radio.startBeacon(start->intervalTu, start->beacon));
    } else if (const auto* update = std::get_if<ipc::UpdateBeacon>(&message)) {
        report(radio.updateBeacon(update->beacon));
    } else if (std::holds_alternative<ipc::StopBeacon>(message)) {
        report(radio.stopBeacon());
    } else if (const auto* tx = std::get_if<ipc::TxFrame>(&message)) {
        report(radio.txFrame(tx->rate, tx->retryLimit, tx->flags, tx->frame));
    } else if (std::holds_alternative<ipc::Shutdown>(message)) {
        return false;
    } else if (std::holds_alternative<ipc::Hello>(message)) {
        logLocal(ipc::kLogWarn, "ignoring repeated HELLO");
    } else {
        logLocal(ipc::kLogWarn, std::string("ignoring unexpected message from app: ") +
                                    ipc::nameOf(ipc::typeOf(message)));
    }
    return true;
}

/** Serves the connection until SHUTDOWN, EOF, a signal or a fatal error; returns the exit code. */
int runSession(Socket s, Radio& radio, Sender& sender) {
    auto lastStatus = std::chrono::steady_clock::now();
    for (;;) {
        if (g_stopRequested != 0) {
            logLocal(ipc::kLogInfo, "signal received, shutting down");
            return kExitOk;
        }
        if (radio.hasFatalError()) {
            return kExitRadio;
        }
        if (sender.lost()) {
            return kExitOk;  // the app closed the socket; shut the radio down cleanly
        }

        const auto now = std::chrono::steady_clock::now();
        if (radio.ready() && now - lastStatus >= kStatusInterval) {
            sender.send(radio.counters());
            lastStatus = now;
        }

        const int readable = waitReadable(s, kTickMs);
        if (readable == 0) {
            continue;
        }
        if (readable < 0) {
            return kExitConnect;
        }

        ipc::Message message;
        std::string why;
        switch (readMessage(s, message, why)) {
            case ReadResult::Ok:
                if (!dispatch(message, radio, sender)) {
                    return kExitOk;
                }
                break;
            case ReadResult::Malformed:
                sendError(sender, ipc::ErrorCode::MalformedMessage, "skipped malformed message: " + why);
                break;
            case ReadResult::Desync:
                sendError(sender, ipc::ErrorCode::ProtocolDesync, "stream out of sync: " + why);
                return kExitConnect;
            case ReadResult::Eof:
                logLocal(ipc::kLogInfo, "app closed the socket");
                return kExitOk;
            case ReadResult::IoError:
                // A reset or broken connection (Windows reports a closed AF_UNIX peer this way) means the
                // app is gone. Shut the radio down cleanly like on EOF.
                std::fprintf(stderr, "radiod: connection to the app lost\n");
                return kExitOk;
        }
    }
}

int runRadiod(const Args& args) {
    NetworkRuntime network;
    if (!network.ok()) {
        std::fprintf(stderr, "radiod: network runtime (Winsock) failed to start\n");
        return kExitConnect;
    }

    UnixAddress limits;
    if (args.socketPath.size() >= sizeof(limits.sun_path)) {
        // AF_UNIX paths are limited (108 bytes on Windows and Linux); fail fast with a clear message.
        std::fprintf(stderr, "radiod: socket path is %zu bytes, the limit is %zu: %s\n", args.socketPath.size(),
                     sizeof(limits.sun_path) - 1, args.socketPath.c_str());
        return kExitConnect;
    }

    Socket socket = kInvalidSocket;
    for (int attempt = 0; attempt < kConnectAttempts && socket == kInvalidSocket; ++attempt) {
        socket = connectUnix(args.socketPath);
        if (socket == kInvalidSocket) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kConnectRetryMs));
        }
    }
    if (socket == kInvalidSocket) {
        std::fprintf(stderr, "radiod: cannot connect to socket %s\n", args.socketPath.c_str());
        return kExitConnect;
    }

    int exitCode = kExitOk;
    {
        Sender sender(socket);
        g_sender = &sender;

        RadioOptions options;
        options.retryLimit = args.retryLimit;
        options.verbose = args.verbose;
        Radio radio([&sender](const ipc::Message& message) { sender.send(message); }, options);

        LogPump pump;
        if (pump.ok()) {
            radio.redirectDiagnostics(pump.stream());
            pump.start();
        }
        if (!performHandshake(socket, sender, args.token)) {
            exitCode = kExitConnect;
        } else {
            // Nothing is sent to the app until it has proven itself with the session token.
            for (const std::string& ignored : args.ignored) {
                logLocal(ipc::kLogDebug, "ignoring unknown argument: " + ignored);
            }
            logLocal(ipc::kLogInfo, std::string("radiod up: protocol=") + std::to_string(ipc::kProtocolVersion) +
                                        " devourer=" + RADIOD_DEVOURER_VERSION);
            try {
                exitCode = runSession(socket, radio, sender);
            } catch (const std::exception& e) {
                sendError(sender, ipc::ErrorCode::Internal, std::string("unexpected exception: ") + e.what());
                exitCode = kExitInternal;
            }
        }

        radio.shutdown();
        if (pump.ok()) {
            radio.redirectDiagnostics(stderr);  // devourer must not write to the pipe once it is closed
            pump.stop();
        }
        g_sender = nullptr;
    }
    closeSocketGracefully(socket, kCloseDrainMs);
    return exitCode;
}

}  // namespace

int main(int argc, char** argv) {
    Args args;
    const std::string usageError = parseArgs(argc, argv, args);
    if (!usageError.empty()) {
        std::fprintf(stderr, "radiod: %s\n", usageError.c_str());
        printUsage();
        return kExitUsage;
    }
    if (args.help) {
        printUsage();
        return kExitOk;
    }
    if (args.version) {
        std::printf("radiod protocol %u devourer %s\n", static_cast<unsigned>(ipc::kProtocolVersion),
                    RADIOD_DEVOURER_VERSION);
        return kExitOk;
    }

    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);

    try {
        return runRadiod(args);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "radiod: fatal: %s\n", e.what());
        return kExitInternal;
    }
}
