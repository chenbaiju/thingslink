#include "baijulink/connection.hpp"
#include <algorithm>
#include <cstring>

namespace baijulink {
namespace {
bool alnum(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}
bool valid_host(std::string_view value) {
    if (value.empty() || value.size() > 253) return false;
    bool numeric = true;
    for (char c : value) if ((c < '0' || c > '9') && c != '.') numeric = false;
    unsigned labels = 0;
    for (std::size_t pos = 0; pos < value.size();) {
        auto end = value.find('.', pos);
        if (end == std::string_view::npos) end = value.size();
        auto label = value.substr(pos, end - pos);
        if (label.empty() || label.size() > 63 || !alnum(label.front()) || !alnum(label.back())) return false;
        for (char c : label) if (!alnum(c) && c != '-') return false;
        if (numeric) {
            if (label.size() > 3 || (label.size() > 1 && label.front() == '0')) return false;
            unsigned octet = 0;
            for (char c : label) octet = octet * 10 + static_cast<unsigned>(c - '0');
            if (octet > 255) return false;
        }
        ++labels;
        if (end == value.size()) break;
        pos = end + 1;
        if (pos == value.size()) return false;
    }
    return !numeric || labels == 4;
}
bool pem_shape(std::string_view pem) {
    constexpr std::string_view begin = "-----BEGIN CERTIFICATE-----";
    constexpr std::string_view end = "-----END CERTIFICATE-----";
    if (pem.empty() || pem.size() > ConnectionProfile::max_ca_bytes) return false;
    auto whitespace = [](char c) { return c == ' ' || c == '\r' || c == '\n' || c == '\t'; };
    unsigned certs = 0;
    while (!pem.empty()) {
        while (!pem.empty() && whitespace(pem.front())) pem.remove_prefix(1);
        if (pem.empty()) break;
        if (pem.substr(0, begin.size()) != begin) return false;
        pem.remove_prefix(begin.size());
        auto finish = pem.find(end);
        if (finish == std::string_view::npos || finish == 0) return false;
        unsigned body = 0;
        for (char c : pem.substr(0, finish)) {
            if (whitespace(c)) continue;
            if (!alnum(c) && c != '+' && c != '/' && c != '=') return false;
            ++body;
        }
        if (body == 0) return false;
        pem.remove_prefix(finish + end.size());
        ++certs;
    }
    // Syntax screening only. The pinned TLS stack must parse/verify X.509.
    return certs != 0;
}
template<std::size_t N> void copy(std::array<char, N>& out, std::string_view in) {
    std::memcpy(out.data(), in.data(), in.size());
    out[in.size()] = 0;
}
}
void ConnectionProfile::clear() {
    // Volatile stores prevent dead-store elimination of our own credential copy.
    // The vendor library also owns heap copies; no whole-heap erasure claim.
    volatile char* secret = token_.data();
    for (std::size_t i = 0; i < token_.size(); ++i) secret[i] = 0;
    host_.fill(0); username_.fill(0); client_id_.fill(0); ca_.fill(0); port_ = 0;
}
ProfileResult ConnectionProfile::assign(const ConnectionInput& input) {
    clear();
    if (!valid_identity(input.identity)) return ProfileResult::Identity;
    if (!valid_host(input.host)) return ProfileResult::Host;
    if (input.port == 0 || input.port > 65535) return ProfileResult::Port;
    if (input.access_token.empty() || input.access_token.size() > 256) return ProfileResult::Token;
    for (unsigned char c : input.access_token) if (c < 33 || c > 126) return ProfileResult::Token;
    if (!pem_shape(input.ca_pem)) return ProfileResult::Certificate;
    baijulink::username(input.identity, username_);
    copy(host_, input.host); copy(token_, input.access_token); copy(ca_, input.ca_pem);
    std::memcpy(client_id_.data(), "bl-", 3);
    std::memcpy(client_id_.data() + 3, username_.data(), std::strlen(username_.data()) + 1);
    port_ = input.port;
    return ProfileResult::Ok;
}
bool plausible_utc(std::uint64_t utc_ms) {
    return utc_ms >= 1577836800000ULL && utc_ms <= 253402300799999ULL;
}
std::uint32_t ReconnectBackoff::next_delay_ms(std::uint32_t entropy) {
    auto ceiling = std::min<std::uint32_t>(60000, 1000U << std::min(failures_, 6U));
    if (failures_ < 6) ++failures_;
    // Equal jitter: never hot-loop; half to full exponential delay, capped at 60s.
    return ceiling / 2 + entropy % (ceiling / 2 + 1);
}
}
