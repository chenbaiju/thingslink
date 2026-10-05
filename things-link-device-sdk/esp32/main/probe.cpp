#include "thingslink/protocol.hpp"
#include "esp_log.h"
#include "esp_psram.h"
#include "sdkconfig.h"
#include <cstring>

// Build/board probe only. These public deterministic vectors are not credentials,
// a live clock, or a source of production UUID entropy. No networking or GPIO.
extern "C" void app_main() {
    static thingslink::Outbound report;
    std::array<char, 37> id{};
    const std::array<std::uint8_t, 10> test_entropy{};
    constexpr std::uint64_t test_time = 1790812800123ULL;
    auto result = thingslink::uuid7(test_time, true, test_entropy, id);
    if (result == thingslink::Error::Ok)
        result = thingslink::property_report({"probe", "board"}, id.data(), test_time, true,
                                          "{\"relay\":false}", report, "1.0.0");
    if (result != thingslink::Error::Ok || !std::strstr(report.payload.data(), "2026-10-01T00:00:00.123Z")) {
        ESP_LOGE("sdk_probe", "Protocol vector failed: %d", static_cast<int>(result));
        return;
    }
    if (esp_psram_get_size() != 8 * 1024 * 1024) {
        ESP_LOGE("sdk_probe", "N8R8 PSRAM size mismatch: %u", static_cast<unsigned>(esp_psram_get_size()));
        return;
    }
    ESP_LOGI("sdk_probe", "Protocol probe passed; target=%s, PSRAM=%u bytes", CONFIG_IDF_TARGET,
             static_cast<unsigned>(esp_psram_get_size()));
    ESP_LOGI("sdk_probe", "Build/board probe only; network, storage and relay acceptance remain pending");
}
