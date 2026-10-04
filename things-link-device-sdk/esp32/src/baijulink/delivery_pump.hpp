#pragma once
#include "command_journal.hpp"

namespace baijulink {
class PublishPort {
public:
    virtual ~PublishPort() = default;
    // QoS1/non-retained, one wire attempt; never calls DeliveryPump inline.
    // Callbacks must be queued until this function returns its packet ID.
    virtual int publish(const Outbound&) = 0;
};
enum class PumpResult { Idle, Sent, Waiting, Reconnect, Suspended, StorageFailure, StaleAck, Confirmed };
// One application worker owns the pump, stores and command journal. Statically/heap allocate.
class DeliveryPump {
public:
    DeliveryPump(Outbox& queue, CommandJournal& journal) : queue_(queue), journal_(journal) {}
    bool connected(std::uint64_t generation); // Only after successful QoS1 SUBACK.
    void disconnected(std::uint64_t generation);
    PumpResult step(std::uint64_t utc_ms, bool trusted, std::uint64_t monotonic_ms, PublishPort&);
    PumpResult acknowledge(std::uint64_t generation, int packet_id, std::uint64_t monotonic_ms);
    bool in_flight() const { return packet_id_ != 0; }
private:
    Outbox& queue_;
    CommandJournal& journal_;
    Delivery delivery_{};
    Outbound result_{};
    std::uint64_t generation_{}, journal_sequence_{}, sent_at_{}, last_tick_{};
    int packet_id_{}, last_packet_id_{};
    bool online_{}, have_tick_{};
    PumpResult fail(PumpResult result);
    bool tick(std::uint64_t now);
};
}
