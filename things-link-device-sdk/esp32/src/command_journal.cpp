#include "thingslink/command_journal.hpp"
#include "snapshot_codec.hpp"
#include "buffers.hpp"
#include <limits>

namespace thingslink {
using namespace detail;
namespace {
bool valid_request(const Downlink& request) {
    auto rn = length(request.request_id), kn = length(request.command_key), bn = length(request.body);
    if (!valid_uuid7({request.request_id.data(), rn}) || !bn || bn >= request.body.size() || kn >= request.command_key.size()) return false;
    return (request.kind == RequestKind::Command && kn > 0) || (request.kind == RequestKind::PropertySet && kn == 0);
}
bool same_request(const Downlink& a, const Downlink& b) {
    // Compare bounded strings, not unused padding supplied by a caller.
    return a.kind == b.kind && std::strcmp(a.request_id.data(), b.request_id.data()) == 0
        && std::strcmp(a.command_key.data(), b.command_key.data()) == 0 && std::strcmp(a.body.data(), b.body.data()) == 0;
}
std::uint64_t request_time(const Downlink& request) {
    std::uint64_t time = 0;
    for (unsigned i = 0; i < 13; ++i) {
        char c = request.request_id[i]; if (c == '-') continue;
        time = time * 16 + static_cast<unsigned>(c <= '9' ? c - '0' : c - 'a' + 10);
    }
    return time;
}
bool fresh(const Downlink& request, std::uint64_t now) {
    auto time = request_time(request);
    return time > now ? time - now <= CommandJournal::future_skew_ms : now - time <= CommandJournal::admission_age_ms;
}
bool reply_route(Identity id, const Downlink& request, const Outbound& result) {
    auto tn = length(result.topic), pn = length(result.payload);
    if (!tn || tn >= result.topic.size() || !pn || pn >= result.payload.size()) return false;
    std::array<char, 130> user{}; if (username(id, user) != Error::Ok) return false;
    std::string_view topic(result.topic.data(), tn), owner(user.data());
    if (topic.substr(0, 6) != "tc/v1/" || topic.substr(6, owner.size()) != owner) return false;
    auto suffix = topic.substr(6 + owner.size());
    if (request.kind == RequestKind::PropertySet) return suffix == "/up/property/set/reply";
    return suffix.size() == 54 && suffix.substr(0, 12) == "/up/command/"
        && suffix.substr(12, 36) == std::string_view(request.request_id.data(), 36) && suffix.substr(48) == "/reply";
}
}
JournalResult CommandJournal::save() {
    static_assert(capacity * (8 + 8 + 8 + 1 + 1 + 36 + 1 + 64 + 2 + max_json_bytes + 2 + 2 + max_topic_bytes - 1 + max_json_bytes) + 160 < snapshot_capacity);
    Writer w{scratch_.data()}; w.text("BLC1", 4);
    auto idn = length(identity_); w.number(idn, 1); w.text(identity_.data(), idn);
    w.number(next_sequence_, 8); w.number(last_clock_, 8); w.number(count_, 1);
    for (std::size_t i = 0; i < count_; ++i) {
        const auto& r = records_[i];
        w.number(r.sequence, 8); w.number(r.accepted_at, 8); w.number(r.completed ? (r.reported ? 2 : 1) : 0, 1);
        w.number(r.request.kind == RequestKind::Command ? 1 : 2, 1);
        w.text(r.request.request_id.data(), 36);
        auto kn = length(r.request.command_key), bn = length(r.request.body);
        w.number(kn, 1); w.text(r.request.command_key.data(), kn); w.number(bn, 2); w.text(r.request.body.data(), bn);
        if (r.completed) {
            w.number(r.completed_at, 8);
            auto tn = length(r.result.topic), pn = length(r.result.payload);
            w.number(tn, 2); w.number(pn, 2); w.text(r.result.topic.data(), tn); w.text(r.result.payload.data(), pn);
        }
    }
    w.number(crc(scratch_.data(), w.position), 4);
    if (!store_.replace(scratch_.data(), w.position)) { ready_ = false; active_.fill(false); return JournalResult::StorageFailure; }
    ready_ = true; return JournalResult::Ok;
}
JournalResult CommandJournal::recover(Identity identity) {
    ready_ = false; count_ = 0; next_sequence_ = 1; last_clock_ = 0; active_.fill(false);
    for (auto& record : records_) {
        record.sequence = 0; record.accepted_at = 0; record.completed_at = 0;
        record.completed = false; record.reported = false; clear_request(record.request); clear_output(record.result);
    }
    if (username(identity, identity_) != Error::Ok) return JournalResult::WrongIdentity;
    std::size_t size = 0;
    auto status = store_.read(scratch_.data(), scratch_.size(), size);
    if (status == ReadResult::Failed) return JournalResult::StorageFailure;
    if (status == ReadResult::Missing) return save();
    if (status != ReadResult::Found || size < 26 || size > scratch_.size()) return JournalResult::Corrupt;
    Reader tail{scratch_.data() + size - 4, 4};
    if (tail.number(4) != crc(scratch_.data(), size - 4) || std::memcmp(scratch_.data(), "BLC1", 4)) return JournalResult::Corrupt;
    Reader r{scratch_.data(), size - 4, 4}; std::array<char, 130> owner{};
    auto idn = r.number(1); r.text(owner, idn);
    if (!r.valid) return JournalResult::Corrupt;
    if (owner != identity_) return JournalResult::WrongIdentity;
    next_sequence_ = r.number(8); last_clock_ = r.number(8); count_ = r.number(1);
    if (!next_sequence_ || count_ > capacity || (last_clock_ && !clock_valid(last_clock_))) return JournalResult::Corrupt;
    for (std::size_t i = 0; i < count_; ++i) {
        auto& record = records_[i];
        record.sequence = r.number(8); record.accepted_at = r.number(8);
        auto state = r.number(1), kind = r.number(1);
        if (state > 2 || kind < 1 || kind > 2) return JournalResult::Corrupt;
        record.completed = state != 0; record.reported = state == 2;
        record.request.kind = kind == 1 ? RequestKind::Command : RequestKind::PropertySet;
        r.text(record.request.request_id, 36);
        auto kn = r.number(1); r.text(record.request.command_key, kn);
        auto bn = r.number(2); r.text(record.request.body, bn);
        if (!r.valid || !record.sequence || record.sequence >= next_sequence_ || !clock_valid(record.accepted_at)
            || record.accepted_at > last_clock_ || !valid_request(record.request) || !fresh(record.request, record.accepted_at)) return JournalResult::Corrupt;
        for (std::size_t other = 0; other < i; ++other)
            if (records_[other].sequence == record.sequence || records_[other].request.request_id == record.request.request_id) return JournalResult::Corrupt;
        if (record.completed) {
            record.completed_at = r.number(8);
            auto tn = r.number(2), pn = r.number(2); r.text(record.result.topic, tn); r.text(record.result.payload, pn);
            if (!r.valid || record.completed_at < record.accepted_at || record.completed_at > last_clock_
                || !reply_route(identity, record.request, record.result)) return JournalResult::Corrupt;
        }
    }
    if (!r.valid || r.position != r.size) return JournalResult::Corrupt;
    ready_ = true; return JournalResult::Ok;
}
JournalResult CommandJournal::begin(const Downlink& request, std::uint64_t ms, bool trusted, std::uint64_t& lease, Outbound& cached) {
    lease = 0; clear_output(cached);
    if (!ready_) return JournalResult::NotReady;
    if (!trusted || !clock_valid(ms) || ms < last_clock_) return JournalResult::ClockUntrusted;
    if (!valid_request(request)) return JournalResult::InvalidMessage;
    for (std::size_t i = 0; i < count_; ++i) {
        const auto& r = records_[i];
        if (std::strcmp(r.request.request_id.data(), request.request_id.data())) continue;
        if (!same_request(r.request, request)) return JournalResult::Conflict;
        if (!r.completed) return JournalResult::Uncertain;
        cached = r.result; return JournalResult::Cached;
    }
    if (!fresh(request, ms)) return JournalResult::StaleRequest;
    // Serialize device actions. An unresolved older action must be assessed first.
    for (std::size_t i = 0; i < count_; ++i) if (!records_[i].completed) return JournalResult::Blocked;
    std::size_t slot = count_;
    if (slot == capacity) {
        for (std::size_t i = 0; i < count_; ++i)
            if (records_[i].completed && records_[i].reported && ms - records_[i].completed_at >= retention_ms) { slot = i; break; }
        if (slot == capacity) return JournalResult::Full;
    }
    if (next_sequence_ == std::numeric_limits<std::uint64_t>::max()) return JournalResult::Exhausted;
    auto& r = records_[slot]; r.sequence = next_sequence_++; r.accepted_at = ms; r.request = request;
    r.completed_at = 0; r.completed = false; r.reported = false; clear_output(r.result);
    if (slot == count_) ++count_;
    last_clock_ = ms;
    auto result = save();
    if (result != JournalResult::Ok) return result;
    active_[slot] = true; lease = r.sequence; return JournalResult::Execute;
}
JournalResult CommandJournal::finish(std::size_t slot, const TerminalResult& input) {
    if (!input.clock_trusted || !clock_valid(input.utc_ms) || input.utc_ms < last_clock_) return JournalResult::ClockUntrusted;
    if (input.status != ReplyStatus::Success && input.status != ReplyStatus::Failed) return JournalResult::InvalidMessage;
    std::string_view user(identity_.data()); auto split = user.find('/');
    Identity identity{user.substr(0, split), user.substr(split + 1)};
    auto& r = records_[slot];
    // Generate the result before changing journal state; a malformed result must not consume the lease.
    auto error = reply(identity, r.request, input.status, input.message_id, input.utc_ms, input.clock_trusted,
                       input.output_json, input.error_code, input.message, r.result);
    if (error != Error::Ok) { clear_output(r.result); return JournalResult::InvalidMessage; }
    r.completed = true; r.completed_at = input.utc_ms; last_clock_ = input.utc_ms;
    auto status = save(); active_[slot] = false; return status;
}
JournalResult CommandJournal::complete(std::uint64_t lease, const TerminalResult& result) {
    if (!ready_) return JournalResult::NotReady;
    for (std::size_t i = 0; i < count_; ++i)
        if (lease && records_[i].sequence == lease && active_[i] && !records_[i].completed) return finish(i, result);
    return JournalResult::InvalidLease;
}
JournalResult CommandJournal::resolve_uncertain(std::string_view request_id, const TerminalResult& result) {
    if (!ready_) return JournalResult::NotReady;
    if (!valid_uuid7(request_id)) return JournalResult::InvalidMessage;
    for (std::size_t i = 0; i < count_; ++i)
        if (request_id == std::string_view(records_[i].request.request_id.data()) && !records_[i].completed && !active_[i]) return finish(i, result);
    return JournalResult::InvalidLease;
}
JournalResult CommandJournal::next_result(std::uint64_t& sequence, Outbound& result) const {
    sequence = 0; clear_output(result);
    if (!ready_) return JournalResult::NotReady;
    for (std::size_t i = 0; i < count_; ++i) if (records_[i].completed && !records_[i].reported) {
        sequence = records_[i].sequence; result = records_[i].result; return JournalResult::Ok;
    }
    return JournalResult::Empty;
}
JournalResult CommandJournal::acknowledge_result(std::uint64_t sequence) {
    if (!ready_) return JournalResult::NotReady;
    for (std::size_t i = 0; i < count_; ++i) if (sequence && records_[i].sequence == sequence && records_[i].completed) {
        if (records_[i].reported) return JournalResult::Ok;
        records_[i].reported = true; return save();
    }
    return JournalResult::InvalidLease;
}
}
