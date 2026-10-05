#pragma once
#include "protocol.hpp"
#include <cstddef>

namespace thingslink {
enum class ReadResult { Found, Missing, Failed };
// Single-owner port: atomic replacement, durable before true. False may mean
// either the old or new COMPLETE blob survived. Never map I/O errors to Missing.
class SnapshotStore {
public:
    virtual ~SnapshotStore() = default;
    virtual ReadResult read(std::uint8_t* buffer, std::size_t capacity, std::size_t& size) = 0;
    virtual bool replace(const std::uint8_t* buffer, std::size_t size) = 0;
};
enum class QueueResult { Ok, NotReady, StorageFailure, Corrupt, WrongIdentity,
                         InvalidMessage, ClockUntrusted, Full, Empty, Suspended, StaleAck, Exhausted };
struct Delivery { std::uint64_t sequence{}; Outbound message{}; };
// Single task/owner only. Allocate statically or on heap, not an MCU task stack.
// The MQTT adapter must map PUBACKs to sequence AND its live connection/session.
class Outbox {
public:
    static constexpr std::size_t capacity = 4;
    static constexpr unsigned max_attempts = 8;
    static constexpr std::uint64_t retry_window_ms = 86400000;
    static constexpr std::size_t snapshot_capacity = 18000;
    explicit Outbox(SnapshotStore& store) : store_(store) {}
    Outbox(const Outbox&) = delete;
    Outbox& operator=(const Outbox&) = delete;
    QueueResult recover(Identity identity);
    QueueResult enqueue(const Outbound& message, std::uint64_t utc_ms, bool clock_trusted);
    // Reserves a durable attempt before exposing bytes to the transport.
    QueueResult prepare(std::uint64_t utc_ms, bool clock_trusted, Delivery& delivery);
    QueueResult acknowledge(std::uint64_t sequence);
    bool ready() const { return ready_; }
    std::size_t size() const { return ready_ ? count_ : 0; }
private:
    struct Record { std::uint64_t sequence{}, queued_at{}; unsigned attempts{}; Outbound message{}; };
    SnapshotStore& store_;
    bool ready_{};
    std::array<char, 130> identity_{};
    std::array<Record, capacity> records_{};
    std::array<std::uint8_t, snapshot_capacity> scratch_{};
    std::uint64_t next_sequence_{1};
    std::uint64_t last_clock_{};
    std::size_t count_{};
    QueueResult save();
    bool valid_message(const Outbound& message) const;
};
}
