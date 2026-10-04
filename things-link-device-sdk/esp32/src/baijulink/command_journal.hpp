#pragma once
#include "outbox.hpp"

namespace baijulink {
enum class JournalResult { Ok, Execute, Cached, Uncertain, Conflict, StaleRequest, Full, Blocked, Empty,
                           NotReady, StorageFailure, Corrupt, WrongIdentity, InvalidMessage,
                           ClockUntrusted, InvalidLease, Exhausted };
struct TerminalResult {
    ReplyStatus status;
    std::string_view message_id;
    std::uint64_t utc_ms;
    bool clock_trusted;
    std::string_view output_json, error_code, message;
};
// Single owner; use a DIFFERENT storage namespace/key than Outbox.
// A persisted intent is NOT proof of a physical action or of its completion.
class CommandJournal {
public:
    static constexpr std::size_t capacity = 8, snapshot_capacity = 72000;
    static constexpr std::uint64_t admission_age_ms = 300000, future_skew_ms = 30000, retention_ms = 360000;
    explicit CommandJournal(SnapshotStore& store) : store_(store) {}
    CommandJournal(const CommandJournal&) = delete;
    CommandJournal& operator=(const CommandJournal&) = delete;
    JournalResult recover(Identity identity);
    // Input must be a successful parse_downlink result. Only Execute grants action permission.
    JournalResult begin(const Downlink& request, std::uint64_t utc_ms, bool clock_trusted,
                        std::uint64_t& lease, Outbound& cached);
    JournalResult complete(std::uint64_t lease, const TerminalResult& result);
    // Explicit reconciliation AFTER independent physical-state assessment; never actuates.
    JournalResult resolve_uncertain(std::string_view request_id, const TerminalResult& result);
    // Drain after recovery and before admitting more work. Persist original bytes in Outbox.
    JournalResult next_result(std::uint64_t& sequence, Outbound& result) const;
    // Call only after the transport confirms this exact result. Idempotent while retained.
    JournalResult acknowledge_result(std::uint64_t sequence);
    bool ready() const { return ready_; }
private:
    struct Record {
        std::uint64_t sequence{}, accepted_at{}, completed_at{};
        bool completed{}, reported{};
        Downlink request{};
        Outbound result{};
    };
    SnapshotStore& store_;
    bool ready_{};
    std::array<char, 130> identity_{};
    std::array<Record, capacity> records_{};
    std::array<bool, capacity> active_{}; // Deliberately not persisted.
    std::array<std::uint8_t, snapshot_capacity> scratch_{};
    std::size_t count_{};
    std::uint64_t next_sequence_{1}, last_clock_{};
    JournalResult save();
    JournalResult finish(std::size_t slot, const TerminalResult& result);
};
}
