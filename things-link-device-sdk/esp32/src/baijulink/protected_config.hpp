#pragma once
#include "settings.hpp"
#include "nvs.h"
namespace baijulink {
enum class ConfigStoreResult { Ok, NotProtected, Missing, Failure };
// Single boot owner. Existing encrypted keys + RELEASE flash encryption AND
// secure boot are prerequisites; this class NEVER provisions keys/eFuses.
class ProtectedConfigStore {
public:
    ~ProtectedConfigStore();
    ConfigStoreResult open();
    ConfigStoreResult load(DeviceSettings&);
    ConfigStoreResult save(const DeviceSettings&);
    ProtectedConfigStore()=default;
    ProtectedConfigStore(const ProtectedConfigStore&)=delete;
    ProtectedConfigStore& operator=(const ProtectedConfigStore&)=delete;
private:
    nvs_handle_t handle_{};
    bool ready_{},failed_{};
    std::array<char,DeviceSettings::max_document+1> buffer_{};
    void wipe();
};
}
