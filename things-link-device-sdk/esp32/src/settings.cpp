#include "baijulink/settings.hpp"
#include "cJSON.h"
#include <cstring>
#include <cmath>

namespace baijulink {
namespace {
void wipe(char* data, std::size_t n) { volatile char* p=data; while(n--) *p++=0; }
void clear_tree(cJSON* node) {
    for(auto* p=node;p;p=p->next) {
        if(p->valuestring) wipe(p->valuestring,std::strlen(p->valuestring));
        if(p->child) clear_tree(p->child);
    }
}
struct Tree { cJSON* root{}; ~Tree(){clear_tree(root);cJSON_Delete(root);} };
bool flat_json(std::string_view s) {
    if(s.empty() || s.find('\0')!=std::string_view::npos || s.find("\\u0000")!=std::string_view::npos) return false;
    bool quoted=false, escaped=false; int depth=0;
    for(char c:s) {
        if(quoted) { if(escaped) escaped=false; else if(c=='\\')escaped=true; else if(c=='"')quoted=false; }
        else if(c=='"')quoted=true;
        else if(c=='[' || c==']')return false;
        else if(c=='{' && ++depth>1)return false;
        else if(c=='}' && --depth<0)return false;
    }
    return !quoted && depth==0;
}
cJSON* object(std::string_view json) {
    if(!flat_json(json))return nullptr;
    const char* end=nullptr;
    auto* root=cJSON_ParseWithLengthOpts(json.data(),json.size(),&end,false);
    if(!root)return nullptr;
    bool valid=cJSON_IsObject(root);
    for(const char* p=end;p<json.data()+json.size();++p) if(*p!=' ' && *p!='\n' && *p!='\r' && *p!='\t')valid=false;
    if(!valid){clear_tree(root);cJSON_Delete(root);return nullptr;} return root;
}
template<std::size_t N> bool field(const cJSON* root,const char* name,std::array<char,N>& value) {
    auto* p=cJSON_GetObjectItemCaseSensitive(root,name);
    if(!cJSON_IsString(p) || !p->valuestring)return false;
    const auto n=std::strlen(p->valuestring);
    if(n==0 || n>=N)return false;
    std::memcpy(value.data(),p->valuestring,n+1);return true;
}
std::string_view text(const cJSON* root,const char* name) {
    auto* p=cJSON_GetObjectItemCaseSensitive(root,name);
    return cJSON_IsString(p) && p->valuestring ? p->valuestring : "";
}
}
bool safe_relay_gpio(int gpio) { return gpio==4 || gpio==5 || gpio==6 || gpio==7 || gpio==15 || gpio==16 || gpio==17 || gpio==18; }
void DeviceSettings::clear() {
    wipe(wifi_password.data(),wifi_password.size()); wipe(json_.data(),json_.size());
    ssid.fill(0);project.fill(0);device.fill(0);model.fill(0);profile.clear();size_=0;
    mode=ExampleMode::Connect;relay_gpio=-1;active_high=false;
}
bool DeviceSettings::load(std::string_view json) {
    clear(); if(json.size()>max_document)return false;
    Tree tree{object(json)}; if(!tree.root)return false;
    constexpr const char* names[]={"ssid","wifiPassword","projectKey","deviceKey","host","port","accessToken","caPem","modelVersion","mode","relayGpio","activeHigh"};
    unsigned seen=0;
    for(auto* item=tree.root->child;item;item=item->next) {
        unsigned i=0;for(;i<12;++i)if(item->string && std::strcmp(item->string,names[i])==0)break;
        if(i==12 || (seen & (1U<<i)))return false;
        seen|=1U<<i;
    }
    auto reject=[&] {clear();return false;};
    if((seen & 1023U)!=1023U || !field(tree.root,"ssid",ssid) || !field(tree.root,"wifiPassword",wifi_password) ||
       !field(tree.root,"projectKey",project) || !field(tree.root,"deviceKey",device) || !field(tree.root,"modelVersion",model))return reject();
    const auto length=std::strlen(wifi_password.data());
    if(length<8 || length>63 || !valid_model_version(model.data()))return reject();
    for(unsigned char c:std::string_view(wifi_password.data()))if(c<32 || c>126)return reject();
    auto* port=cJSON_GetObjectItemCaseSensitive(tree.root,"port");
    if(!cJSON_IsNumber(port) || !std::isfinite(port->valuedouble) || port->valuedouble<1 || port->valuedouble>65535 ||
       std::floor(port->valuedouble)!=port->valuedouble)return reject();
    const auto selected=text(tree.root,"mode");
    if(selected=="connect")mode=ExampleMode::Connect;
    else if(selected=="sensor")mode=ExampleMode::Sensor;
    else if(selected=="relay")mode=ExampleMode::Relay;
    else return reject();
    if(mode==ExampleMode::Relay) {
        auto* pin=cJSON_GetObjectItemCaseSensitive(tree.root,"relayGpio");
        auto* level=cJSON_GetObjectItemCaseSensitive(tree.root,"activeHigh");
        if(!cJSON_IsNumber(pin) || !std::isfinite(pin->valuedouble) || pin->valuedouble<0 || pin->valuedouble>48 ||
           pin->valuedouble!=std::floor(pin->valuedouble) || !safe_relay_gpio(static_cast<int>(pin->valuedouble)) || !cJSON_IsBool(level))return reject();
        relay_gpio=static_cast<int>(pin->valuedouble);active_high=cJSON_IsTrue(level);
    } else if(seen & (3U<<10))return reject();
    if(profile.assign({{project.data(),device.data()},text(tree.root,"host"),text(tree.root,"accessToken"),text(tree.root,"caPem"),
                       static_cast<unsigned>(port->valuedouble)})!=ProfileResult::Ok)return reject();
    std::memcpy(json_.data(),json.data(),json.size());size_=json.size();json_[size_]=0;return true;
}
bool ClockGuard::set(std::uint64_t utc,std::uint64_t mono) {
    valid_=plausible_utc(utc);utc_=utc;mono_=last_mono_=mono;return valid_;
}
bool ClockGuard::trusted(std::uint64_t utc,std::uint64_t mono) {
    if(!valid_ || mono<last_mono_ || mono<mono_ || mono-mono_>21600000 || !plausible_utc(utc)) {valid_=false;return false;}
    last_mono_=mono;const auto predicted=utc_+(mono-mono_);
    if((utc>predicted?utc-predicted:predicted-utc)>5000)valid_=false;
    return valid_;
}
bool relay_request(const Downlink& request,bool& on) {
    on=false;
    if(request.kind==RequestKind::Command && std::string_view(request.command_key.data())!="setRelay")return false;
    Tree tree{object(request.body.data())};if(!tree.root)return false;
    auto* field=tree.root->child;
    const char* key=request.kind==RequestKind::Command?"on":"relay";
    if(!field || field->next || !field->string || std::strcmp(field->string,key)!=0 || !cJSON_IsBool(field))return false;
    on=cJSON_IsTrue(field);return true;
}
}
