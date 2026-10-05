#include "thingslink/delivery_pump.hpp"

namespace thingslink {
bool DeliveryPump::connected(std::uint64_t generation) {
    if (generation == 0 || generation <= generation_) return false;
    generation_ = generation; online_ = true; packet_id_ = last_packet_id_ = 0;
    journal_sequence_ = 0; return true;
}
void DeliveryPump::disconnected(std::uint64_t generation) {
    if (generation == generation_) { online_ = false; packet_id_ = 0; journal_sequence_ = 0; }
}
PumpResult DeliveryPump::fail(PumpResult result) {
    online_ = false; packet_id_ = 0; journal_sequence_ = 0; return result;
}
bool DeliveryPump::tick(std::uint64_t now) {
    if (have_tick_ && now < last_tick_) return false;
    last_tick_ = now; have_tick_ = true; return true;
}
PumpResult DeliveryPump::step(std::uint64_t utc, bool trusted, std::uint64_t now, PublishPort& port) {
    if (!online_) return PumpResult::Idle;
    if (!tick(now)) return fail(PumpResult::Reconnect);
    if (packet_id_) return now - sent_at_ >= 15000 ? fail(PumpResult::Reconnect) : PumpResult::Waiting;
    if (last_packet_id_ >= 60000) return fail(PumpResult::Reconnect); // Recreate before packet ID wrap.
    std::uint64_t result_sequence = 0;
    const auto pending = journal_.next_result(result_sequence, result_);
    if (pending != JournalResult::Ok && pending != JournalResult::Empty) return fail(PumpResult::StorageFailure);
    if (pending == JournalResult::Ok) {
        const auto enqueue = queue_.enqueue(result_, utc, trusted);
        if (enqueue != QueueResult::Ok && enqueue != QueueResult::Full)
            return fail(enqueue == QueueResult::ClockUntrusted ? PumpResult::Suspended : PumpResult::StorageFailure);
    }
    const auto prepared = queue_.prepare(utc, trusted, delivery_);
    if (prepared == QueueResult::Empty) return PumpResult::Idle;
    if (prepared != QueueResult::Ok)
        return fail(prepared == QueueResult::Suspended || prepared == QueueResult::ClockUntrusted
                    ? PumpResult::Suspended : PumpResult::StorageFailure);
    journal_sequence_ = pending == JournalResult::Ok && delivery_.message.topic == result_.topic &&
                        delivery_.message.payload == result_.payload ? result_sequence : 0;
    const int packet = port.publish(delivery_.message);
    // Fixed transport uses incremental IDs; duplicates/wrap are never associated to new records.
    if (packet <= last_packet_id_ || packet <= 0 || packet > 65535) return fail(PumpResult::Reconnect);
    packet_id_ = last_packet_id_ = packet; sent_at_ = now;
    return PumpResult::Sent;
}
PumpResult DeliveryPump::acknowledge(std::uint64_t generation, int packet, std::uint64_t now) {
    if (!online_ || generation != generation_ || packet_id_ == 0 || packet != packet_id_) return PumpResult::StaleAck;
    if (!tick(now) || now - sent_at_ >= 15000) return fail(PumpResult::Reconnect);
    if (queue_.acknowledge(delivery_.sequence) != QueueResult::Ok) return fail(PumpResult::StorageFailure);
    if (journal_sequence_ && journal_.acknowledge_result(journal_sequence_) != JournalResult::Ok)
        return fail(PumpResult::StorageFailure);
    packet_id_ = 0; journal_sequence_ = 0;
    return PumpResult::Confirmed;
}
}
