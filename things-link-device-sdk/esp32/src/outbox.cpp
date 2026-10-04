#include "baijulink/outbox.hpp"
#include "snapshot_codec.hpp"
#include "buffers.hpp"
#include <cstring>
#include <limits>

namespace baijulink {
using namespace detail;

bool Outbox::valid_message(const Outbound& message) const {
    auto tn = length(message.topic), pn = length(message.payload), idn = length(identity_);
    if (!tn || tn >= message.topic.size() || !pn || pn >= message.payload.size()) return false;
    std::string_view topic(message.topic.data(), tn);
    constexpr std::string_view prefix = "tc/v1/";
    if (topic.substr(0, prefix.size()) != prefix || topic.substr(prefix.size(), idn) != std::string_view(identity_.data(), idn)
        || topic.substr(prefix.size() + idn, 4) != "/up/") return false;
    return topic.find_first_of("+#") == std::string_view::npos && tn > prefix.size() + idn + 4;
}
QueueResult Outbox::save() {
    // Maximum encoded record is 4372 bytes, plus a <160 byte header and CRC.
    static_assert(capacity * (8 + 8 + 1 + 2 + 2 + max_topic_bytes - 1 + max_json_bytes) + 160 < snapshot_capacity);
    Writer w{scratch_.data()};
    w.text("BLO1", 4);
    auto idn = length(identity_);
    w.number(idn, 1); w.text(identity_.data(), idn);
    w.number(next_sequence_, 8); w.number(last_clock_, 8); w.number(count_, 1);
    for (std::size_t i = 0; i < count_; ++i) {
        const auto& r = records_[i];
        w.number(r.sequence, 8); w.number(r.queued_at, 8); w.number(r.attempts, 1);
        auto tn = length(r.message.topic), pn = length(r.message.payload);
        w.number(tn, 2); w.number(pn, 2);
        w.text(r.message.topic.data(), tn); w.text(r.message.payload.data(), pn);
    }
    w.number(crc(scratch_.data(), w.position), 4);
    if (!store_.replace(scratch_.data(), w.position)) {
        ready_ = false; // Never expose possibly uncommitted in-memory state.
        return QueueResult::StorageFailure;
    }
    ready_ = true;
    return QueueResult::Ok;
}
QueueResult Outbox::recover(Identity identity) {
    ready_ = false; count_ = 0; next_sequence_ = 1; last_clock_ = 0;
    for (auto& record : records_) {
        record.sequence = 0; record.queued_at = 0; record.attempts = 0; clear_output(record.message);
    }
    if (username(identity, identity_) != Error::Ok) return QueueResult::WrongIdentity;
    std::size_t size = 0;
    auto result = store_.read(scratch_.data(), scratch_.size(), size);
    if (result == ReadResult::Failed) return QueueResult::StorageFailure;
    if (result == ReadResult::Missing) return save();
    if (result != ReadResult::Found || size < 26 || size > scratch_.size()) return QueueResult::Corrupt;
    Reader tail{scratch_.data() + size - 4, 4};
    if (tail.number(4) != crc(scratch_.data(), size - 4) || std::memcmp(scratch_.data(), "BLO1", 4)) return QueueResult::Corrupt;
    Reader r{scratch_.data(), size - 4, 4};
    std::array<char, 130> stored_identity{};
    auto idn = r.number(1); r.text(stored_identity, idn);
    if (!r.valid) return QueueResult::Corrupt;
    if (stored_identity != identity_) return QueueResult::WrongIdentity;
    next_sequence_ = r.number(8); last_clock_ = r.number(8); count_ = r.number(1);
    if (!next_sequence_ || count_ > capacity || (last_clock_ && !clock_valid(last_clock_))) return QueueResult::Corrupt;
    std::uint64_t previous = 0;
    for (std::size_t i = 0; i < count_; ++i) {
        auto& record = records_[i];
        record.sequence = r.number(8); record.queued_at = r.number(8); record.attempts = static_cast<unsigned>(r.number(1));
        auto tn = r.number(2), pn = r.number(2);
        r.text(record.message.topic, tn); r.text(record.message.payload, pn);
        if (!r.valid || record.sequence <= previous || record.sequence >= next_sequence_ || record.attempts > max_attempts
            || !clock_valid(record.queued_at) || record.queued_at > last_clock_ || !valid_message(record.message)) return QueueResult::Corrupt;
        previous = record.sequence;
    }
    if (!r.valid || r.position != r.size) return QueueResult::Corrupt;
    ready_ = true;
    return QueueResult::Ok;
}
QueueResult Outbox::enqueue(const Outbound& message, std::uint64_t ms, bool trusted) {
    if (!ready_) return QueueResult::NotReady;
    if (!trusted || !clock_valid(ms) || ms < last_clock_) return QueueResult::ClockUntrusted;
    if (!valid_message(message)) return QueueResult::InvalidMessage;
    // Recovery may re-offer a journal result before its original PUBACK arrives.
    // Preserve the original age and attempt budget; never append the same bytes twice.
    for (std::size_t i = 0; i < count_; ++i)
        if (!std::strcmp(records_[i].message.topic.data(), message.topic.data())
            && !std::strcmp(records_[i].message.payload.data(), message.payload.data())) return QueueResult::Ok;
    if (count_ == capacity) return QueueResult::Full;
    if (next_sequence_ == std::numeric_limits<std::uint64_t>::max()) return QueueResult::Exhausted;
    auto& record = records_[count_++];
    record.sequence = next_sequence_++; record.queued_at = ms; record.attempts = 0; record.message = message;
    last_clock_ = ms;
    return save();
}
QueueResult Outbox::prepare(std::uint64_t ms, bool trusted, Delivery& delivery) {
    delivery.sequence = 0; clear_output(delivery.message);
    if (!ready_) return QueueResult::NotReady;
    if (!trusted || !clock_valid(ms) || ms < last_clock_) return QueueResult::ClockUntrusted;
    if (!count_) return QueueResult::Empty;
    auto& record = records_[0];
    if (record.attempts >= max_attempts || ms - record.queued_at >= retry_window_ms) return QueueResult::Suspended;
    ++record.attempts; last_clock_ = ms;
    auto result = save();
    if (result == QueueResult::Ok) { delivery.sequence = record.sequence; delivery.message = record.message; }
    return result;
}
QueueResult Outbox::acknowledge(std::uint64_t sequence) {
    if (!ready_) return QueueResult::NotReady;
    if (!count_ || !sequence || records_[0].sequence != sequence || !records_[0].attempts) return QueueResult::StaleAck;
    for (std::size_t i = 1; i < count_; ++i) records_[i - 1] = records_[i];
    auto& removed = records_[--count_];
    removed.sequence = 0; removed.queued_at = 0; removed.attempts = 0; clear_output(removed.message);
    return save();
}
}
