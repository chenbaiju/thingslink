#pragma once
#include "connection.hpp"
namespace baijulink {
enum class ExampleMode { Connect, Sensor, Relay };
// A single boot owns these secret copies; never log or serialize the C++ object.
class DeviceSettings {
public:
    static constexpr std::size_t max_document = 12288;
    DeviceSettings() = default;
    ~DeviceSettings() { clear(); }
    DeviceSettings(const DeviceSettings&) = delete;
    DeviceSettings& operator=(const DeviceSettings&) = delete;
    bool load(std::string_view json);
    void clear();
    bool ready() const { return profile.ready(); }
    ConnectionInput connection() const { return {{project.data(),device.data()},profile.host(),profile.token(),profile.ca(),profile.port()}; }
    std::string_view document() const { return {json_.data(), size_}; }
    std::array<char,33> ssid{};
    std::array<char,65> wifi_password{}, project{}, device{}, model{};
    ConnectionProfile profile;
    ExampleMode mode{ExampleMode::Connect};
    int relay_gpio{-1};
    bool active_high{};
private:
    std::array<char,max_document+1> json_{};
    std::size_t size_{};
};
bool safe_relay_gpio(int gpio);
// USB grants a bounded development clock lease, NOT authenticated network time.
class ClockGuard {
public:
    bool set(std::uint64_t utc_ms, std::uint64_t mono_ms);
    bool trusted(std::uint64_t utc_ms, std::uint64_t mono_ms);
private:
    std::uint64_t utc_{}, mono_{}, last_mono_{};
    bool valid_{};
};
// Only absolute booleans: command setRelay {on:bool}, property/set {relay:bool}.
bool relay_request(const Downlink& request, bool& on);
}
