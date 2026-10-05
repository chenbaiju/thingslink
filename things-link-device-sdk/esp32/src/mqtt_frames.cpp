#include "thingslink/mqtt_frames.hpp"
#include "buffers.hpp"
#include <cstring>

namespace thingslink {
void MqttFrames::reset() {
    assembling_ = false; packet_id_ = 0; total_ = 0; received_ = 0;
    topic_.fill(0); payload_.fill(0);
}
bool MqttFrames::connected(std::uint64_t generation) {
    if (!generation || generation <= generation_) return false;
    generation_ = generation; connected_ = true; reset(); return true;
}
void MqttFrames::disconnected(std::uint64_t generation) {
    if (generation == generation_) { connected_ = false; reset(); }
}
FrameResult MqttFrames::consume(Identity identity, const MqttPart& p, Downlink& output) {
    detail::clear_request(output);
    // Stale callbacks must not reset an in-progress frame in the new session.
    if (!connected_ || p.session != generation_) return {FrameStatus::Rejected};
    auto reject = [this]() { reset(); return FrameResult{FrameStatus::Rejected}; };
    if (p.packet_id < 1 || p.packet_id > 65535 || p.qos != 1 || p.retained || p.total < 1
        || p.total > static_cast<int>(max_json_bytes) || p.offset < 0 || p.offset > p.total
        || p.data.empty() || p.data.size() > static_cast<std::size_t>(p.total - p.offset)
        || p.topic.size() >= topic_.size() || p.topic.find('\0') != std::string_view::npos) return reject();
    if (p.offset == 0) {
        // An overlapping first fragment cannot be disambiguated from an old
        // continuation if the peer reuses the packet id. Drop both assemblies.
        if (p.topic.empty() || assembling_) return reject();
        reset(); packet_id_ = p.packet_id; total_ = p.total; assembling_ = true;
        std::memcpy(topic_.data(), p.topic.data(), p.topic.size());
    } else if (!assembling_ || p.packet_id != packet_id_ || p.total != total_ || p.offset != received_
               || (!p.topic.empty() && p.topic != std::string_view(topic_.data()))) return reject();
    std::memcpy(payload_.data() + received_, p.data.data(), p.data.size());
    received_ += static_cast<int>(p.data.size());
    if (received_ != total_) return {FrameStatus::Incomplete};
    auto error = parse_downlink(identity, topic_.data(), std::string_view(payload_.data(), static_cast<std::size_t>(total_)), output);
    reset();
    if (error != Error::Ok) { detail::clear_request(output); return {FrameStatus::Rejected, error}; }
    return {FrameStatus::Complete};
}
}
