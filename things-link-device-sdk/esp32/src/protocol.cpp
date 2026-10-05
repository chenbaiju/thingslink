#include "thingslink/protocol.hpp"
#include "buffers.hpp"
#include "cJSON.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>

namespace thingslink {
static_assert(sizeof(std::time_t) >= 8, "SDK requires a 64-bit UTC time_t");
namespace {
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
Json owned(cJSON* p) { return Json(p, cJSON_Delete); }
bool alnum(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }
bool key(std::string_view s, bool first_alnum) {
    if (s.empty() || s.size() > 64 || (first_alnum && !alnum(s.front()))) return false;
    for (char c : s) if (!alnum(c) && c != '_' && c != '-') return false;
    return true;
}
bool model_version(std::string_view version) {
    unsigned parts = 0; std::size_t start = 0;
    while (start < version.size()) {
        auto end = version.find('.', start); if (end == std::string_view::npos) end = version.size();
        auto part = version.substr(start, end - start);
        if (part.empty() || part.size() > 10 || (part.size() > 1 && part.front() == '0')) return false;
        std::uint64_t value = 0;
        for (char c : part) { if (c < '0' || c > '9') return false; value = value * 10 + static_cast<unsigned>(c - '0'); }
        if (value > 2147483647 || ++parts > 3) return false;
        if (end == version.size()) return parts == 3;
        start = end + 1;
    }
    return false;
}
bool utf8(std::string_view s) {
    for (std::size_t i = 0; i < s.size();) {
        auto c = static_cast<unsigned char>(s[i++]);
        if (c == 0) return false;
        if (c < 0x80) continue;
        unsigned count = 0; std::uint32_t value = 0, minimum = 0;
        if (c >= 0xc2 && c <= 0xdf) { count = 1; value = c & 31; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { count = 2; value = c & 15; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { count = 3; value = c & 7; minimum = 0x10000; }
        else return false;
        if (i + count > s.size()) return false;
        while (count--) { auto d = static_cast<unsigned char>(s[i++]); if ((d & 0xc0) != 0x80) return false; value = (value << 6) | (d & 63); }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}
// Bound nesting before the JSON library allocates or recursively descends; reject embedded NUL strings.
bool lexical(std::string_view s) {
    if (!utf8(s)) return false;
    int depth = 0; bool in_string = false;
    for (std::size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (in_string && c == '\\') {
            if (++i >= s.size()) return false;
            if (s[i] == 'u' && s.substr(i, 5) == "u0000") return false;
            continue;
        }
        if (c == '"') { in_string = !in_string; continue; }
        if (!in_string && (c == '{' || c == '[') && ++depth > 16) return false;
        if (!in_string && (c == '}' || c == ']') && --depth < 0) return false;
    }
    return !in_string && depth == 0;
}
bool tree(const cJSON* node, unsigned& count) {
    if (!node || ++count > 256 || (cJSON_IsNumber(node) && (!std::isfinite(node->valuedouble) || std::fabs(node->valuedouble) > 9007199254740991.0))) return false;
    for (auto child = node->child; child; child = child->next) {
        if (cJSON_IsObject(node)) {
            if (!child->string) return false;
            for (auto other = child->next; other; other = other->next)
                if (other->string && std::strcmp(child->string, other->string) == 0) return false;
        }
        if (!tree(child, count)) return false;
    }
    return true;
}
Json parse(std::string_view value) {
    if (value.empty() || value.size() > max_json_bytes || !lexical(value)) return owned(nullptr);
    const char* end = nullptr;
    auto result = owned(cJSON_ParseWithLengthOpts(value.data(), value.size(), &end, false));
    if (!result || !end) return owned(nullptr);
    while (end < value.data() + value.size() && (*end == ' ' || *end == '\n' || *end == '\r' || *end == '\t')) ++end;
    unsigned count = 0;
    if (end != value.data() + value.size() || !tree(result.get(), count)) return owned(nullptr);
    return result;
}
bool properties(const cJSON* object) {
    if (!cJSON_IsObject(object) || !object->child) return false;
    unsigned count = 0;
    for (auto node = object->child; node; node = node->next)
        if (++count > 64 || !key(node->string, false)) return false;
    return true;
}
std::string_view string(const cJSON* object, const char* name) {
    const auto field = cJSON_GetObjectItemCaseSensitive(object, name);
    return cJSON_IsString(field) && field->valuestring ? std::string_view(field->valuestring) : std::string_view{};
}
bool add(cJSON* object, const char* name, std::string_view value) {
    // All caller strings have independent bounds and no embedded NUL.
    std::array<char, 501> buffer{};
    if (value.size() >= buffer.size() || value.find('\0') != std::string_view::npos) return false;
    std::memcpy(buffer.data(), value.data(), value.size());
    return cJSON_AddStringToObject(object, name, buffer.data()) != nullptr;
}
Error time_string(std::uint64_t ms, bool trusted, std::array<char, 25>& output) {
    if (!trusted || ms < 1577836800000ULL || ms > 253402300799999ULL) return Error::ClockUntrusted;
    std::time_t seconds = static_cast<std::time_t>(ms / 1000);
    std::tm utc{};
#ifdef _WIN32
    if (gmtime_s(&utc, &seconds) != 0) return Error::ClockUntrusted;
#else
    if (!gmtime_r(&seconds, &utc)) return Error::ClockUntrusted;
#endif
    int size = std::snprintf(output.data(), output.size(), "%04d-%02d-%02dT%02d:%02d:%02d.%03uZ", utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday, utc.tm_hour, utc.tm_min, utc.tm_sec, static_cast<unsigned>(ms % 1000));
    return size == 24 ? Error::Ok : Error::ClockUntrusted;
}
Error topic(Identity id, std::string_view suffix, std::array<char, max_topic_bytes>& output) {
    output.fill(0);
    if (!valid_identity(id)) return Error::InvalidIdentity;
    int n = std::snprintf(output.data(), output.size(), "tc/v1/%.*s/%.*s/%.*s", static_cast<int>(id.project_key.size()), id.project_key.data(), static_cast<int>(id.device_key.size()), id.device_key.data(), static_cast<int>(suffix.size()), suffix.data());
    return n > 0 && static_cast<std::size_t>(n) < output.size() ? Error::Ok : Error::TooLarge;
}
Error envelope(cJSON* object, std::string_view id, std::uint64_t ms, bool trusted) {
    if (!valid_uuid7(id)) return Error::InvalidId;
    std::array<char, 25> time{};
    auto error = time_string(ms, trusted, time);
    if (error != Error::Ok) return error;
    return object && add(object, "messageId", id) && add(object, "occurredAt", time.data()) ? Error::Ok : Error::NoMemory;
}
Error print(cJSON* object, std::array<char, max_json_bytes + 1>& output) {
    if (!cJSON_PrintPreallocated(object, output.data(), static_cast<int>(output.size()), false)) { output.fill(0); return Error::TooLarge; }
    return Error::Ok;
}
}
bool valid_identity(Identity id) { return key(id.project_key, true) && key(id.device_key, true); }
bool valid_model_version(std::string_view version) { return model_version(version); }
bool valid_uuid7(std::string_view id) {
    if (id.size() != 36 || id[14] != '7' || (id[19] != '8' && id[19] != '9' && id[19] != 'a' && id[19] != 'b')) return false;
    for (std::size_t i = 0; i < id.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (id[i] != '-') return false; }
        else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return false;
    }
    return true;
}
Error uuid7(std::uint64_t ms, bool trusted, const std::array<std::uint8_t, 10>& entropy, std::array<char, 37>& output) {
    output.fill(0); std::array<char, 25> time{};
    if (time_string(ms, trusted, time) != Error::Ok) return Error::ClockUntrusted;
    std::array<std::uint8_t, 16> bytes{};
    for (unsigned i = 0; i < 6; ++i) bytes[i] = static_cast<std::uint8_t>(ms >> (40 - i * 8));
    for (unsigned i = 0; i < 10; ++i) bytes[6 + i] = entropy[i];
    bytes[6] = (bytes[6] & 15) | 0x70; bytes[8] = (bytes[8] & 63) | 0x80;
    constexpr char hex[] = "0123456789abcdef"; std::size_t pos = 0;
    for (unsigned i = 0; i < 16; ++i) { if (i == 4 || i == 6 || i == 8 || i == 10) output[pos++] = '-'; output[pos++] = hex[bytes[i] >> 4]; output[pos++] = hex[bytes[i] & 15]; }
    return Error::Ok;
}
Error username(Identity id, std::array<char, 130>& output) {
    output.fill(0); if (!valid_identity(id)) return Error::InvalidIdentity;
    std::snprintf(output.data(), output.size(), "%.*s/%.*s", static_cast<int>(id.project_key.size()), id.project_key.data(), static_cast<int>(id.device_key.size()), id.device_key.data()); return Error::Ok;
}
Error subscription(Identity id, std::array<char, max_topic_bytes>& output) { return topic(id, "down/#", output); }
Error property_report(Identity id, std::string_view message_id, std::uint64_t ms, bool trusted, std::string_view json, Outbound& output, std::string_view version) {
    detail::clear_output(output); auto error = topic(id, "up/property/report", output.topic); if (error != Error::Ok) return error;
    if (!model_version(version)) return Error::InvalidMessage;
    auto body = parse(json); if (!body || !properties(body.get())) return json.size() > max_json_bytes ? Error::TooLarge : Error::InvalidJson;
    auto root = owned(cJSON_CreateObject()); error = envelope(root.get(), message_id, ms, trusted); if (error != Error::Ok) return error;
    if (!add(root.get(), "modelVersion", version)) return Error::NoMemory;
    if (!cJSON_AddItemToObject(root.get(), "payload", body.get())) return Error::NoMemory;
    body.release(); return print(root.get(), output.payload);
}
Error parse_downlink(Identity id, std::string_view incoming_topic, std::string_view json, Downlink& output) {
    detail::clear_request(output); std::array<char, max_topic_bytes> prefix{};
    auto error = topic(id, "down/", prefix); if (error != Error::Ok) return error;
    std::string_view base(prefix.data());
    if (incoming_topic.substr(0, base.size()) != base) return Error::UnsupportedTopic;
    auto suffix = incoming_topic.substr(base.size());
    auto root = parse(json); if (!root || !cJSON_IsObject(root.get())) return json.size() > max_json_bytes ? Error::TooLarge : Error::InvalidJson;
    if (string(root.get(), "targetDeviceKey") != id.device_key) return Error::WrongDevice;
    const auto attempt = cJSON_GetObjectItemCaseSensitive(root.get(), "attempt");
    if (attempt && (!cJSON_IsNumber(attempt) || attempt->valuedouble < 1 || attempt->valuedouble > 2147483647 || std::floor(attempt->valuedouble) != attempt->valuedouble)) return Error::InvalidMessage;
    const cJSON* body = nullptr; std::string_view request;
    if (suffix.substr(0, 8) == "command/") {
        request = suffix.substr(8); output.kind = RequestKind::Command;
        auto command = string(root.get(), "commandKey"); if (!key(command, false)) return Error::InvalidMessage;
        std::memcpy(output.command_key.data(), command.data(), command.size());
        body = cJSON_GetObjectItemCaseSensitive(root.get(), "input"); if (!cJSON_IsObject(body)) return Error::InvalidMessage;
    } else if (suffix == "property/set") {
        output.kind = RequestKind::PropertySet; request = string(root.get(), "requestId");
        body = cJSON_GetObjectItemCaseSensitive(root.get(), "properties"); if (!properties(body)) return Error::InvalidMessage;
    } else return Error::UnsupportedTopic;
    if (!valid_uuid7(request)) return Error::InvalidId;
    std::memcpy(output.request_id.data(), request.data(), request.size());
    return print(const_cast<cJSON*>(body), output.body);
}
Error reply(Identity id, const Downlink& request, ReplyStatus status, std::string_view message_id, std::uint64_t ms, bool trusted, std::string_view json, std::string_view code, std::string_view message, Outbound& output) {
    detail::clear_output(output);
    std::size_t bounded_length = 0;
    while (bounded_length < request.request_id.size() && request.request_id[bounded_length]) ++bounded_length;
    if (!valid_uuid7(std::string_view(request.request_id.data(), bounded_length))) return Error::InvalidId;
    if (status != ReplyStatus::Ack && status != ReplyStatus::Success && status != ReplyStatus::Failed) return Error::InvalidMessage;
    if ((status != ReplyStatus::Failed && (!code.empty() || !message.empty())) || code.size() > 64 || message.size() > 500 || !utf8(code) || !utf8(message)) return Error::InvalidMessage;
    std::array<char, 80> suffix{};
    if (request.kind == RequestKind::Command) std::snprintf(suffix.data(), suffix.size(), "up/command/%s/reply", request.request_id.data());
    else if (request.kind == RequestKind::PropertySet) std::snprintf(suffix.data(), suffix.size(), "up/property/set/reply");
    else return Error::InvalidMessage;
    auto error = topic(id, suffix.data(), output.topic); if (error != Error::Ok) return error;
    auto body = parse(json); if (!body || !cJSON_IsObject(body.get())) return Error::InvalidJson;
    auto root = owned(cJSON_CreateObject()); error = envelope(root.get(), message_id, ms, trusted); if (error != Error::Ok) return error;
    const char* name = status == ReplyStatus::Ack ? "ACK" : status == ReplyStatus::Success ? "SUCCESS" : "FAILED";
    if (!add(root.get(), "status", name) || (request.kind == RequestKind::PropertySet && !add(root.get(), "requestId", request.request_id.data()))) return Error::NoMemory;
    if ((!code.empty() && !add(root.get(), "errorCode", code)) || (!message.empty() && !add(root.get(), "message", message))) return Error::NoMemory;
    if (!cJSON_AddItemToObject(root.get(), "output", body.get())) return Error::NoMemory;
    body.release(); return print(root.get(), output.payload);
}
}
