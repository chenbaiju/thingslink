#pragma once
#include <array>
#include <cstdint>
#include <string_view>

// Internal working namespace; this is not an Arduino/library publication contract.
namespace thingslink {
constexpr std::size_t max_json_bytes = 4096;
constexpr std::size_t max_topic_bytes = 256;
enum class Error { Ok, InvalidIdentity, InvalidId, ClockUntrusted, InvalidJson, InvalidMessage, WrongDevice, UnsupportedTopic, TooLarge, NoMemory };
enum class RequestKind { Command, PropertySet };
enum class ReplyStatus { Ack, Success, Failed };
struct Identity { std::string_view project_key; std::string_view device_key; };
struct Outbound {
    std::array<char, max_topic_bytes> topic{};
    std::array<char, max_json_bytes + 1> payload{};
    static constexpr int qos = 1;
    static constexpr bool retained = false;
};
struct Downlink {
    RequestKind kind{RequestKind::Command};
    std::array<char, 37> request_id{};
    std::array<char, 65> command_key{};
    // Validated input/properties object, not the whole envelope or attempt counter.
    std::array<char, max_json_bytes + 1> body{};
};
bool valid_identity(Identity identity);
bool valid_model_version(std::string_view version);
bool valid_uuid7(std::string_view id);
// Entropy must be supplied by the target CSPRNG; callers generate once and persist before sending.
Error uuid7(std::uint64_t utc_ms, bool clock_trusted, const std::array<std::uint8_t, 10>& entropy, std::array<char, 37>& output);
Error username(Identity identity, std::array<char, 130>& output);
Error subscription(Identity identity, std::array<char, max_topic_bytes>& output);
Error property_report(Identity identity, std::string_view message_id, std::uint64_t utc_ms, bool clock_trusted, std::string_view properties_json, Outbound& output, std::string_view model_version);
Error parse_downlink(Identity identity, std::string_view topic, std::string_view json, Downlink& output);
Error reply(Identity identity, const Downlink& request, ReplyStatus status, std::string_view message_id,
            std::uint64_t utc_ms, bool clock_trusted, std::string_view output_json,
            std::string_view error_code, std::string_view message, Outbound& output);
}
