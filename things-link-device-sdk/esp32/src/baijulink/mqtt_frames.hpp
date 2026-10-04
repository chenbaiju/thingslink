#pragma once
#include "protocol.hpp"

namespace baijulink {
enum class FrameStatus { Incomplete, Complete, Rejected };
struct FrameResult { FrameStatus status; Error protocol_error{Error::Ok}; };
struct MqttPart {
    std::uint64_t session;
    int packet_id, qos, offset, total;
    bool retained;
    std::string_view topic, data;
};
// Single MQTT event owner. Buffers are members, never multi-kilobyte stack frames.
// The target adapter must unregister/drain old callbacks before destroying this object.
class MqttFrames {
public:
    bool connected(std::uint64_t generation);
    void disconnected(std::uint64_t generation);
    FrameResult consume(Identity identity, const MqttPart& part, Downlink& output);
private:
    std::uint64_t generation_{};
    bool connected_{}, assembling_{};
    int packet_id_{}, total_{}, received_{};
    std::array<char, max_topic_bytes> topic_{};
    std::array<char, max_json_bytes + 1> payload_{};
    void reset();
};
}
