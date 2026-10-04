#include "baijulink/example_logic.hpp"
#include <cstdio>
namespace baijulink {
bool ExampleLogic::start(const DeviceSettings& settings) {
    if(!safe_off() || !settings.ready())return false;
    mode_=settings.mode;sample_=0;on_=false;
    if(mode_!=ExampleMode::Relay){configured_=false;return true;}
    configured_=port_.configure_off(settings.relay_gpio,settings.active_high);return configured_;
}
bool ExampleLogic::safe_off() {
    if(configured_ && !port_.write(false))return false;
    on_=false;return true;
}
ActionResult ExampleLogic::execute(const Downlink& request,std::array<char,128>& output) {
    output.fill(0);bool on=false;
    if(mode_!=ExampleMode::Relay || !configured_ || !relay_request(request,on))return ActionResult::Unsupported;
    if(!port_.write(on))return ActionResult::HardwareFailure;
    on_=on;std::snprintf(output.data(),output.size(),"{\"relay\":%s}",on_?"true":"false");return ActionResult::Success;
}
bool ExampleLogic::report(std::array<char,128>& output) {
    output.fill(0);
    if(mode_==ExampleMode::Connect)return false;
    if(mode_==ExampleMode::Relay) {
        if(!configured_)return false;
        std::snprintf(output.data(),output.size(),"{\"relay\":%s}",on_?"true":"false");
    } else {
        const unsigned tenths=200+(sample_++%40);
        std::snprintf(output.data(),output.size(),"{\"temperature\":%u.%u}",tenths/10,tenths%10);
    }
    return true;
}
}
