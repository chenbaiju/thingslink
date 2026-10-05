#include "thingslink/esp_mqtt_connection.hpp"
#include "sdkconfig.h"
#include <ctime>
#include <limits>
#include <cstdio>

#if defined(CONFIG_ESP_TLS_SKIP_SERVER_CERT_VERIFY) || !defined(CONFIG_MBEDTLS_HAVE_TIME_DATE)
#error "ThingsLink requires server certificate and certificate validity-time verification"
#endif
#if !defined(CONFIG_MQTT_CUSTOM_OUTBOX) || !defined(CONFIG_MQTT_MSG_ID_INCREMENTAL)
#error "ThingsLink requires the controlled outbox policy and incremental packet IDs"
#endif
#if !defined(CONFIG_MQTT_TRANSPORT_SSL) || !defined(CONFIG_MQTT_PROTOCOL_311)
#error "ThingsLink requires MQTT 3.1.1 over TLS"
#endif

namespace thingslink {
ConnectResult EspMqttConnection::start(const ConnectionInput& input, std::uint64_t utc, bool trusted,
                                     ConnectionObserver observer, void* context, MessageObserver messages) {
    if (client_) return ConnectResult::Busy;
    // TLS validates against the target's real wall clock, not merely the supplied timestamp.
    auto seconds = std::time(nullptr);
    auto actual = seconds < 0 ? 0 : static_cast<std::uint64_t>(seconds) * 1000;
    auto delta = actual > utc ? actual - utc : utc - actual;
    if (!trusted || !plausible_utc(utc) || !plausible_utc(actual) || delta > 5000)
        return ConnectResult::ClockUntrusted;
    if (generation_ == std::numeric_limits<std::uint64_t>::max()) return ConnectResult::Exhausted;
    if (profile_.assign(input) != ProfileResult::Ok) return ConnectResult::InvalidProfile;
    esp_mqtt_client_config_t config{};
    config.broker.address.hostname = profile_.host();
    config.broker.address.port = profile_.port();
    config.broker.address.transport = MQTT_TRANSPORT_OVER_SSL;
    config.broker.verification.certificate = profile_.ca();
    // NULL common_name means verify the configured host; never offer an insecure switch.
    config.broker.verification.skip_cert_common_name_check = false;
    config.credentials.username = profile_.username();
    config.credentials.client_id = profile_.client_id();
    config.credentials.authentication.password = profile_.token();
    config.session.protocol_ver = MQTT_PROTOCOL_V_3_1_1;
    config.session.keepalive = 30;
    config.session.disable_clean_session = false;
    config.network.disable_auto_reconnect = true;
    config.network.timeout_ms = 10000;
    config.task.stack_size = 8192;
    config.buffer.size = 1024;
    config.buffer.out_size = 4608;
    config.outbox.limit = 8192;
    client_ = esp_mqtt_client_init(&config);
    if (!client_) { profile_.clear(); return ConnectResult::LibraryFailure; }
    observer_ = observer; messages_ = messages; context_ = context; terminal_ = false; connected_ = false;
    ++generation_;
    if (esp_mqtt_client_register_event(client_, MQTT_EVENT_ANY, event, this) != ESP_OK ||
        esp_mqtt_client_start(client_) != ESP_OK) {
        stop(); return ConnectResult::LibraryFailure;
    }
    return ConnectResult::Ok;
}
void EspMqttConnection::stop() {
    if (client_) {
        // Pinned vendor destroy stops and joins its task before freeing the client.
        // Keep CA/observer context alive until that join has completed.
        esp_mqtt_client_destroy(client_);
        client_ = nullptr;
    }
    connected_ = false; observer_ = nullptr; messages_ = nullptr; context_ = nullptr; profile_.clear();
}
int EspMqttConnection::subscribe() {
    if (!client_ || !connected_) return -1;
    char topic[max_topic_bytes];
    const int length = std::snprintf(topic, sizeof topic, "tc/v1/%s/down/#", profile_.username());
    return length > 0 && length < static_cast<int>(sizeof topic) ? esp_mqtt_client_subscribe(client_, topic, 1) : -1;
}
int EspMqttConnection::publish(const Outbound& message) {
    if (!client_ || !connected_) return -1;
    const auto topic_len = strnlen(message.topic.data(), message.topic.size());
    const auto size = strnlen(message.payload.data(), message.payload.size());
    if (!topic_len || topic_len >= message.topic.size() || !size || size >= message.payload.size()) return -1;
    return esp_mqtt_client_publish(client_, message.topic.data(), message.payload.data(), static_cast<int>(size), 1, false);
}
void EspMqttConnection::event(void* self, esp_event_base_t, std::int32_t id, void* data) {
    auto& connection = *static_cast<EspMqttConnection*>(self);
    auto* e = static_cast<esp_mqtt_event_t*>(data);
    if (!e || e->client != connection.client_ || connection.terminal_) return;
    ConnectionEvent result;
    switch (id) {
        case MQTT_EVENT_CONNECTED:
            if (e->session_present || e->protocol_ver != MQTT_PROTOCOL_V_3_1_1) {
                result = ConnectionEvent::ConfigurationRejected; connection.terminal_ = true;
            } else { result = ConnectionEvent::Connected; connection.connected_ = true; }
            break;
        case MQTT_EVENT_DISCONNECTED:
            connection.terminal_ = true; result = ConnectionEvent::Disconnected; break;
        case MQTT_EVENT_ERROR:
            connection.terminal_ = true; result = ConnectionEvent::TransportError;
            if (e->error_handle) {
                auto& error = *e->error_handle;
                if (error.error_type == MQTT_ERROR_TYPE_CONNECTION_REFUSED) {
                    auto code = error.connect_return_code;
                    if (code == MQTT_CONNECTION_REFUSE_BAD_USERNAME || code == MQTT_CONNECTION_REFUSE_NOT_AUTHORIZED)
                        result = ConnectionEvent::AuthenticationRejected;
                    else if (code != MQTT_CONNECTION_REFUSE_SERVER_UNAVAILABLE)
                        result = ConnectionEvent::ConfigurationRejected;
                } else if (error.error_type == MQTT_ERROR_TYPE_TCP_TRANSPORT && error.esp_tls_cert_verify_flags != 0)
                    result = ConnectionEvent::CertificateRejected;
            }
            break;
        case MQTT_EVENT_SUBSCRIBED:
            if (!e->data || e->data_len != 1 || static_cast<unsigned char>(e->data[0]) != 1) {
                connection.terminal_ = true; result = ConnectionEvent::ConfigurationRejected; break;
            }
            [[fallthrough]];
        case MQTT_EVENT_PUBLISHED:
        case MQTT_EVENT_DELETED:
        case MQTT_EVENT_DATA: {
            MessageNotice notice{};
            notice.event = id == MQTT_EVENT_SUBSCRIBED ? MessageEvent::Subscribed :
                           id == MQTT_EVENT_PUBLISHED ? MessageEvent::Published :
                           id == MQTT_EVENT_DELETED ? MessageEvent::Expired : MessageEvent::Data;
            notice.packet_id = e->msg_id;
            if (id == MQTT_EVENT_DATA) {
                if (e->topic_len < 0 || e->data_len < 0 || (!e->topic && e->topic_len) || (!e->data && e->data_len)) {
                    connection.terminal_ = true; result = ConnectionEvent::ConfigurationRejected; break;
                }
                notice.qos = e->qos; notice.retained = e->retain;
                notice.offset = e->current_data_offset; notice.total = e->total_data_len;
                if (e->topic_len) notice.topic = {e->topic, static_cast<std::size_t>(e->topic_len)};
                if (e->data_len) notice.data = {e->data, static_cast<std::size_t>(e->data_len)};
            }
            if (connection.messages_) connection.messages_(connection.context_, connection.generation_, notice);
            return;
        }
        default: return;
    }
    if (connection.terminal_) connection.connected_ = false;
    if (connection.observer_) connection.observer_(connection.context_, connection.generation_, result);
}
}
