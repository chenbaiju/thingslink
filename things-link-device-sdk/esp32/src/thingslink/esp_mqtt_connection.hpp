#pragma once
#include "connection.hpp"
#include "mqtt_client.h"
#include "delivery_pump.hpp"
#include <atomic>

namespace thingslink {
enum class ConnectResult { Ok, Busy, InvalidProfile, ClockUntrusted, Exhausted, LibraryFailure };
enum class ConnectionEvent { Connected, Disconnected, AuthenticationRejected, CertificateRejected,
                             ConfigurationRejected, TransportError };
using ConnectionObserver = void (*)(void*, std::uint64_t generation, ConnectionEvent);
enum class MessageEvent { Subscribed, Published, Data, Expired };
struct MessageNotice {
    MessageEvent event;
    int packet_id{}, qos{}, offset{}, total{};
    bool retained{};
    std::string_view topic, data;
};
using MessageObserver = void (*)(void*, std::uint64_t, const MessageNotice&);
// All methods/destruction belong to ONE control task, never to an MQTT callback.
// Observer runs in the MQTT task: copy the notification to an application queue;
// no blocking, destruction, re-entry, or retaining borrowed event pointers.
class EspMqttConnection : public PublishPort {
public:
    EspMqttConnection() = default;
    ~EspMqttConnection() { stop(); }
    EspMqttConnection(const EspMqttConnection&) = delete;
    EspMqttConnection& operator=(const EspMqttConnection&) = delete;
    ConnectResult start(const ConnectionInput&, std::uint64_t trusted_utc_ms, bool clock_trusted,
                        ConnectionObserver observer, void* context, MessageObserver messages = nullptr);
    void stop();
    int subscribe();
    int publish(const Outbound&) override;
    std::uint64_t generation() const { return generation_; }
private:
    ConnectionProfile profile_;
    esp_mqtt_client_handle_t client_{};
    ConnectionObserver observer_{};
    MessageObserver messages_{};
    std::atomic<bool> connected_{false};
    void* context_{};
    std::uint64_t generation_{};
    bool terminal_{}; // MQTT task only after start; cleared after prior task is joined.
    static void event(void*, esp_event_base_t, std::int32_t, void*);
};
}
