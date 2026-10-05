#include "thingslink/protected_config.hpp"
#include "nvs_flash.h"
#include "esp_flash_encrypt.h"
#include "esp_secure_boot.h"

namespace thingslink {
void ProtectedConfigStore::wipe() {volatile char* p=buffer_.data();for(std::size_t i=0;i<buffer_.size();++i)p[i]=0;}
ProtectedConfigStore::~ProtectedConfigStore(){if(ready_)nvs_close(handle_);wipe();}
ConfigStoreResult ProtectedConfigStore::open() {
    if(failed_)return ConfigStoreResult::Failure;
    if(ready_)return ConfigStoreResult::Ok;
    if(esp_get_flash_encryption_mode()!=ESP_FLASH_ENC_MODE_RELEASE || !esp_secure_boot_enabled())return ConfigStoreResult::NotProtected;
    const auto* keys=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_NVS_KEYS,"bl_keys");
    if(!keys || !keys->encrypted)return ConfigStoreResult::NotProtected;
    // The IDF secure-init API returns success for an already initialized PLAINTEXT
    // partition too. Refuse any pre-existing owner instead of inheriting its mode.
    nvs_stats_t stats{};
    if(nvs_get_stats("bl_creds",&stats)!=ESP_ERR_NVS_NOT_INITIALIZED){failed_=true;return ConfigStoreResult::Failure;}
    nvs_sec_cfg_t key{};
    auto error=nvs_flash_read_security_cfg(keys,&key);
    if(error==ESP_OK)error=nvs_flash_secure_init_partition("bl_creds",&key);
    volatile unsigned char* p=reinterpret_cast<volatile unsigned char*>(&key);
    for(std::size_t i=0;i<sizeof key;++i)p[i]=0;
    if(error!=ESP_OK || nvs_open_from_partition("bl_creds","sdk_config",NVS_READWRITE,&handle_)!=ESP_OK) {
        failed_=true;return ConfigStoreResult::Failure;
    }
    ready_=true;return ConfigStoreResult::Ok;
}
ConfigStoreResult ProtectedConfigStore::load(DeviceSettings& settings) {
    settings.clear();auto state=open();if(state!=ConfigStoreResult::Ok)return state;
    std::size_t size=0;auto error=nvs_get_blob(handle_,"settings",nullptr,&size);
    if(error==ESP_ERR_NVS_NOT_FOUND)return ConfigStoreResult::Missing;
    if(error!=ESP_OK || size==0 || size>DeviceSettings::max_document){failed_=true;return ConfigStoreResult::Failure;}
    auto actual=size;error=nvs_get_blob(handle_,"settings",buffer_.data(),&actual);
    const bool loaded=error==ESP_OK && actual==size && settings.load({buffer_.data(),actual});
    wipe();if(!loaded){failed_=true;return ConfigStoreResult::Failure;}return ConfigStoreResult::Ok;
}
ConfigStoreResult ProtectedConfigStore::save(const DeviceSettings& settings) {
    if(!settings.ready())return ConfigStoreResult::Failure;
    auto state=open();if(state!=ConfigStoreResult::Ok)return state;
    auto doc=settings.document();auto error=nvs_set_blob(handle_,"settings",doc.data(),doc.size());
    if(error==ESP_OK)error=nvs_commit(handle_);
    if(error!=ESP_OK){failed_=true;return ConfigStoreResult::Failure;}return ConfigStoreResult::Ok;
}
}
