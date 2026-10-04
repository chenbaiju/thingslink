#include "baijulink/esp_wifi_station.hpp"
#include "esp_wifi.h"
#include <cstring>
namespace baijulink {
bool EspWifiStation::start(const DeviceSettings& settings) {
    if(initialized_ || !settings.ready())return false;
    static bool netif_initialized=false; // One station owner in this application.
    if(!netif_initialized){if(esp_netif_init()!=ESP_OK)return false;netif_initialized=true;}
    const auto loop=esp_event_loop_create_default();
    if(loop!=ESP_OK && loop!=ESP_ERR_INVALID_STATE)return false;
    interface_=esp_netif_create_default_wifi_sta();if(!interface_)return false;
    wifi_init_config_t init=WIFI_INIT_CONFIG_DEFAULT();init.nvs_enable=0;
    if(esp_wifi_init(&init)!=ESP_OK){stop();return false;}initialized_=true;
    if(esp_event_handler_instance_register(WIFI_EVENT,ESP_EVENT_ANY_ID,event,this,&wifi_handler_)!=ESP_OK ||
       esp_event_handler_instance_register(IP_EVENT,ESP_EVENT_ANY_ID,event,this,&ip_handler_)!=ESP_OK){stop();return false;}
    wifi_config_t config{};
    std::memcpy(config.sta.ssid,settings.ssid.data(),std::strlen(settings.ssid.data()));
    std::memcpy(config.sta.password,settings.wifi_password.data(),std::strlen(settings.wifi_password.data()));
    config.sta.threshold.authmode=WIFI_AUTH_WPA2_PSK;
    config.sta.pmf_cfg.capable=true;config.sta.pmf_cfg.required=false;
    auto error=esp_wifi_set_storage(WIFI_STORAGE_RAM);
    if(error==ESP_OK)error=esp_wifi_set_mode(WIFI_MODE_STA);
    if(error==ESP_OK)error=esp_wifi_set_config(WIFI_IF_STA,&config);
    volatile unsigned char* secret=config.sta.password;for(std::size_t i=0;i<sizeof config.sta.password;++i)secret[i]=0;
    if(error!=ESP_OK){stop();return false;}
    auth_failed_=false;online_=false;connecting_=true;
    if(esp_wifi_start()!=ESP_OK){stop();return false;}started_=true;
    if(esp_wifi_connect()!=ESP_OK){stop();return false;}
    return true;
}
bool EspWifiStation::reconnect() {
    if(!started_ || online_ || connecting_ || auth_failed_)return false;
    connecting_=true;
    if(esp_wifi_connect()!=ESP_OK){connecting_=false;return false;}
    return true;
}
void EspWifiStation::stop() {
    if(started_)esp_wifi_stop();
    if(wifi_handler_)esp_event_handler_instance_unregister(WIFI_EVENT,ESP_EVENT_ANY_ID,wifi_handler_);
    if(ip_handler_)esp_event_handler_instance_unregister(IP_EVENT,ESP_EVENT_ANY_ID,ip_handler_);
    wifi_handler_=ip_handler_=nullptr;
    if(initialized_)esp_wifi_deinit();
    if(interface_)esp_netif_destroy_default_wifi(interface_);
    interface_=nullptr;initialized_=started_=false;online_=connecting_=false;
}
void EspWifiStation::event(void* arg,esp_event_base_t base,std::int32_t id,void* data) {
    auto& self=*static_cast<EspWifiStation*>(arg);
    if(base==IP_EVENT && id==IP_EVENT_STA_GOT_IP) {
        if(data && static_cast<ip_event_got_ip_t*>(data)->esp_netif==self.interface_){self.online_=true;self.connecting_=false;}
    }
    else if((base==WIFI_EVENT && id==WIFI_EVENT_STA_DISCONNECTED) || (base==IP_EVENT && id==IP_EVENT_STA_LOST_IP)) {
        self.online_=false;self.connecting_=false;
        if(base==WIFI_EVENT && data) {
            const auto reason=static_cast<wifi_event_sta_disconnected_t*>(data)->reason;
            if(reason==WIFI_REASON_AUTH_FAIL || reason==WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT || reason==WIFI_REASON_HANDSHAKE_TIMEOUT)
                self.auth_failed_=true;
        }
    }
}
}
