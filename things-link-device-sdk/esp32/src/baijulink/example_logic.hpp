#pragma once
#include "settings.hpp"
namespace baijulink {
class RelayPort {
public:
    virtual ~RelayPort()=default;
    // Must establish the OFF latch before enabling output. Never restore saved ON.
    virtual bool configure_off(int gpio,bool active_high)=0;
    virtual bool write(bool on)=0;
};
enum class ActionResult { Success, Unsupported, HardwareFailure };
class ExampleLogic {
public:
    explicit ExampleLogic(RelayPort& port):port_(port){}
    bool start(const DeviceSettings&);
    bool safe_off();
    ActionResult execute(const Downlink&,std::array<char,128>& output);
    bool report(std::array<char,128>& output);
private:
    RelayPort& port_;
    ExampleMode mode_{ExampleMode::Connect};
    bool configured_{},on_{};
    unsigned sample_{};
};
}
