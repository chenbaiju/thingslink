#pragma once
#include "baijulink/protocol.hpp"

namespace baijulink::detail {
// Aggregate assignment can materialize a multi-kilobyte temporary on Xtensa.
inline void clear_output(Outbound& output) { output.topic.fill(0); output.payload.fill(0); }
inline void clear_request(Downlink& request) {
    request.kind = RequestKind::Command;
    request.request_id.fill(0); request.command_key.fill(0); request.body.fill(0);
}
}
