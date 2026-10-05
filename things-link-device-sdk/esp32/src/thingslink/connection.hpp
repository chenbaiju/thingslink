#pragma once
#include "protocol.hpp"

namespace thingslink {
struct ConnectionInput {
    Identity identity;
    std::string_view host, access_token, ca_pem;
    unsigned port{8883};
};
enum class ProfileResult { Ok, Identity, Host, Port, Token, Certificate };
// Owns all strings passed to the TLS client. Never serialize this object or log it.
// Single control-task owner; do not mutate a profile borrowed by a live client.
class ConnectionProfile {
public:
    static constexpr std::size_t max_ca_bytes = 8192;
    ConnectionProfile() = default;
    ~ConnectionProfile() { clear(); }
    ConnectionProfile(const ConnectionProfile&) = delete;
    ConnectionProfile& operator=(const ConnectionProfile&) = delete;
    ProfileResult assign(const ConnectionInput& input);
    void clear();
    bool ready() const { return port_ != 0; }
    const char* host() const { return host_.data(); }
    const char* username() const { return username_.data(); }
    const char* client_id() const { return client_id_.data(); }
    const char* token() const { return token_.data(); }
    const char* ca() const { return ca_.data(); }
    unsigned port() const { return port_; }
private:
    std::array<char, 254> host_{};
    std::array<char, 130> username_{};
    std::array<char, 134> client_id_{};
    std::array<char, 257> token_{};
    std::array<char, max_ca_bytes + 1> ca_{};
    unsigned port_{};
};
// A plausible UTC range is necessary but NOT a proof of a trusted time source.
bool plausible_utc(std::uint64_t utc_ms);
// Retry schedule only. Authentication/certificate refusals must not call this
// automatically. Supply target CSPRNG entropy, and monotonic (not wall) time.
class ReconnectBackoff {
public:
    std::uint32_t next_delay_ms(std::uint32_t entropy);
    void reset() { failures_ = 0; }
private:
    unsigned failures_{};
};
}
