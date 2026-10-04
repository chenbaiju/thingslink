#pragma once
#include "settings.hpp"
#include "esp_event.h"
#include "esp_netif.h"
#include <atomic>
namespace baijulink {
// Single application owner; credentials only in RAM, never ESP-WiFi default NVS.
class EspWifiStation {
public:
    EspWifiStation()=default;
    ~EspWifiStation(){stop();}
    EspWifiStation(const EspWifiStation&)=delete;
    EspWifiStation& operator=(const EspWifiStation&)=delete;
    bool start(const DeviceSettings&);
    bool reconnect(); // Application must apply bounded backoff, no tight retry loop.
    void stop();
    bool online() const{return online_;}
    bool connecting() const{return connecting_;}
    bool authentication_failed() const{return auth_failed_;}
private:
    esp_netif_t* interface_{};
    esp_event_handler_instance_t wifi_handler_{},ip_handler_{};
    bool initialized_{},started_{};
    std::atomic<bool> online_{false},connecting_{false},auth_failed_{false};
    static void event(void*,esp_event_base_t,std::int32_t,void*);
};
}
