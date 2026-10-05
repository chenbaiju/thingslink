#include "thingslink/esp_mqtt_connection.hpp"
#include "thingslink/esp_wifi_station.hpp"
#include "thingslink/example_logic.hpp"
#include "thingslink/mqtt_frames.hpp"
#include "thingslink/nvs_snapshot.hpp"
#include "thingslink/protected_config.hpp"
#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "esp_heap_caps.h"
#include "esp_psram.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include <charconv>
#include <cstring>
#include <new>
#include <sys/time.h>

using namespace thingslink;
namespace {
std::uint64_t monotonic_ms(){return static_cast<std::uint64_t>(esp_timer_get_time()/1000);}
std::uint64_t utc_ms(){timeval now{};gettimeofday(&now,nullptr);return static_cast<std::uint64_t>(now.tv_sec)*1000+now.tv_usec/1000;}
// The USB protocol prints fixed status labels only; never echoes input, SSID,
// credentials, raw commands, stack dumps or stored configuration.
void status(const char* value){usb_serial_jtag_write_bytes(value,std::strlen(value),pdMS_TO_TICKS(100));usb_serial_jtag_write_bytes("\n",1,pdMS_TO_TICKS(100));}
class GpioRelay final:public RelayPort {
public:
    bool configure_off(int gpio,bool active_high) override {
        if(!safe_relay_gpio(gpio))return false;
        pin_=static_cast<gpio_num_t>(gpio);active_high_=active_high;
        if(gpio_set_level(pin_,active_high_?0:1)!=ESP_OK)return false;
        gpio_config_t cfg{};cfg.pin_bit_mask=1ULL<<gpio;cfg.mode=GPIO_MODE_OUTPUT;
        cfg.pull_up_en=GPIO_PULLUP_DISABLE;cfg.pull_down_en=GPIO_PULLDOWN_DISABLE;cfg.intr_type=GPIO_INTR_DISABLE;
        return gpio_config(&cfg)==ESP_OK;
    }
    bool write(bool on) override{return pin_!=GPIO_NUM_NC && gpio_set_level(pin_,on==active_high_?1:0)==ESP_OK;}
private:
    gpio_num_t pin_{GPIO_NUM_NC};bool active_high_{};
};
struct Event {
    bool connection{};std::uint64_t generation{};ConnectionEvent state{};MessageEvent message{};
    int packet{},qos{},offset{},total{},topic_size{},data_size{};bool retained{};
    std::array<char,max_topic_bytes> topic{};
    std::array<char,1024> data{};
};
class DeviceRuntime {
public:
    DeviceSettings settings;
    ProtectedConfigStore protected_store;
    ClockGuard clock;
    bool running{};
    bool initialize(){inbox_=xQueueCreate(8,sizeof(Event));return inbox_!=nullptr;}
    void command(std::string_view line) {
        if(line=="STATUS"){status(running?(ready_?"STATE MQTT_READY":"STATE CONNECTING"):"STATE STOPPED");return;}
        if(line=="STOP"){status(stop()?"OK STOP":"ERR GPIO");return;}
        if(line.substr(0,15)=="RESOLVE_FAILED ") {
            auto request=line.substr(15);const auto now=monotonic_ms();const auto utc=utc_ms();
            if(!running || !ready_ || !valid_uuid7(request) || !clock.trusted(utc,now) || !message_id(utc)) {status("ERR RECONCILE");return;}
            // Explicit operator reconciliation after independently checking the
            // physical state. This path does not actuate and never claims success.
            auto result=journal_.resolve_uncertain(request,{ReplyStatus::Failed,id_.data(),utc,true,"{}","OUTCOME_UNKNOWN","Operator reconciled an uncertain operation"});
            status(result==JournalResult::Ok?"OK RECONCILED":"ERR RECONCILE");return;
        }
        if(running){status("ERR BUSY");return;}
        if(line.substr(0,5)=="TIME ") {
            std::uint64_t value=0;auto text=line.substr(5);auto parsed=std::from_chars(text.data(),text.data()+text.size(),value);
            if(parsed.ec!=std::errc{} || parsed.ptr!=text.data()+text.size() || !plausible_utc(value)){status("ERR TIME");return;}
            timeval target{static_cast<time_t>(value/1000),static_cast<suseconds_t>((value%1000)*1000)};
            if(settimeofday(&target,nullptr)!=0 || !clock.set(value,monotonic_ms())){status("ERR TIME");return;}
            status("OK TIME");return;
        }
        if(line.substr(0,7)=="CONFIG ") {
            if(!logic_.safe_off()){status("ERR GPIO");return;}
            status(settings.load(line.substr(7))?"OK CONFIG_SESSION":"ERR CONFIG");return;
        }
        if(line=="SAVE") {
            auto result=protected_store.save(settings);
            status(result==ConfigStoreResult::Ok?"OK SAVED":result==ConfigStoreResult::NotProtected?"ERR PROTECTION_REQUIRED":"ERR STORAGE");return;
        }
        if(line=="LOAD") {
            if(!logic_.safe_off()){status("ERR GPIO");return;}
            auto result=protected_store.load(settings);
            status(result==ConfigStoreResult::Ok?"OK LOADED":result==ConfigStoreResult::NotProtected?"ERR PROTECTION_REQUIRED":result==ConfigStoreResult::Missing?"ERR MISSING":"ERR STORAGE");return;
        }
        if(line=="RUN"){status(start()?"OK RUN":"ERR START");return;}
        status("ERR COMMAND");
    }
    void poll() {
        if(!running)return;
        const auto now=monotonic_ms();const auto utc=utc_ms();
        if(!clock.trusted(utc,now)){halt("STATE TIME_REQUIRED");return;}
        if(!storage_.ready()){halt("STATE STORAGE_FAULT");return;}
        if(wifi_.authentication_failed()){halt("STATE WIFI_AUTH_REJECTED");return;}
        if(overflow_.exchange(false)){retry(now);status("STATE RX_OVERFLOW");}
        if(!running)return;
        if(!wifi_.online()) {
            if(live_)retry(now);
            if(now>=wifi_deadline_ && wifi_.connecting()){halt("STATE WIFI_TIMEOUT");return;}
            if(!wifi_.connecting() && now>=wifi_retry_at_) {
                wifi_retry_at_=now+wifi_backoff_.next_delay_ms(esp_random());wifi_deadline_=now+30000;
                wifi_.reconnect();
            }
            return;
        }
        if(!live_ && now>=retry_at_) {
            auto result=mqtt_.start(settings.connection(),utc,true,connection_notice,this,message_notice);
            if(result!=ConnectResult::Ok){halt("STATE MQTT_START_FAILED");return;}
            live_=mqtt_.generation();deadline_=now+30000;
        }
        while(xQueueReceive(inbox_,&event_,0)==pdTRUE) {
            if(event_.generation!=live_)continue;
            if(overflow_){retry(now);return;}
            if(event_.connection) {
                if(event_.state==ConnectionEvent::Connected) {
                    if(subscription_!=0){halt("STATE PROTOCOL_REJECTED");return;}
                    subscription_=mqtt_.subscribe();deadline_=now+15000;
                    if(subscription_<=0){retry(now);return;}
                } else if(event_.state==ConnectionEvent::AuthenticationRejected || event_.state==ConnectionEvent::CertificateRejected ||
                          event_.state==ConnectionEvent::ConfigurationRejected){halt("STATE MQTT_CONFIG_REJECTED");return;}
                else {retry(now);return;}
            } else if(event_.message==MessageEvent::Subscribed) {
                if(ready_ || event_.packet!=subscription_ || !frames_.connected(live_) || !pump_.connected(live_)){halt("STATE PROTOCOL_REJECTED");return;}
                ready_=true;stable_at_=now;status("STATE MQTT_READY");
            } else if(event_.message==MessageEvent::Published) {
                const auto ack=pump_.acknowledge(live_,event_.packet,now);
                if(ack==PumpResult::StorageFailure){halt("STATE STORAGE_FAULT");return;}
                if(ack==PumpResult::Reconnect){retry(now);return;}
            } else if(event_.message==MessageEvent::Expired){retry(now);return;}
            else if(ready_) {
                auto parsed=frames_.consume({settings.project.data(),settings.device.data()},
                    {live_,event_.packet,event_.qos,event_.offset,event_.total,event_.retained,
                     {event_.topic.data(),static_cast<std::size_t>(event_.topic_size)},
                     {event_.data.data(),static_cast<std::size_t>(event_.data_size)}},request_);
                if(parsed.status==FrameStatus::Complete && !action(utc,now))return;
            }
        }
        if(live_ && !ready_ && now>=deadline_){retry(now);return;}
        if(!ready_)return;
        if(now-stable_at_>=30000){mqtt_backoff_.reset();wifi_backoff_.reset();}
        if(now>=report_at_) {
            report_at_=now+60000; // Low-frequency demo; never fill Flash with a busy telemetry loop.
            if(logic_.report(output_)) {
                if(!message_id(utc)){halt("STATE ID_FAILED");return;}
                if(property_report({settings.project.data(),settings.device.data()},id_.data(),utc,true,output_.data(),report_,settings.model.data())!=Error::Ok){halt("STATE REPORT_FAILED");return;}
                auto queued=queue_.enqueue(report_,utc,true);
                if(queued!=QueueResult::Ok && queued!=QueueResult::Full){halt("STATE STORAGE_FAULT");return;}
                if(queued==QueueResult::Full)status("STATE REPORT_BACKPRESSURE");
            }
        }
        auto result=pump_.step(utc,true,now,mqtt_);
        if(result==PumpResult::Reconnect)retry(now);
        else if(result==PumpResult::Suspended)halt("STATE DELIVERY_SUSPENDED");
        else if(result==PumpResult::StorageFailure)halt("STATE STORAGE_FAULT");
    }
private:
    NvsRuntime storage_;
    NvsSnapshotStore outgoing_{storage_,SnapshotKind::Outbox},actions_{storage_,SnapshotKind::Commands};
    Outbox queue_{outgoing_};CommandJournal journal_{actions_};DeliveryPump pump_{queue_,journal_};
    EspWifiStation wifi_;EspMqttConnection mqtt_;MqttFrames frames_;
    GpioRelay gpio_;ExampleLogic logic_{gpio_};
    QueueHandle_t inbox_{};std::atomic<bool> overflow_{false};Event event_;
    Downlink request_;Outbound cached_,report_;std::array<char,128> output_{};std::array<char,37> id_{};
    ReconnectBackoff mqtt_backoff_,wifi_backoff_;
    std::uint64_t live_{},deadline_{},retry_at_{},wifi_retry_at_{},wifi_deadline_{},report_at_{},stable_at_{};
    int subscription_{};bool ready_{},stores_open_{};
    static void connection_notice(void* context,std::uint64_t generation,ConnectionEvent state) {
        auto& self=*static_cast<DeviceRuntime*>(context);Event event{};
        event.connection=true;event.generation=generation;event.state=state;
        if(xQueueSend(self.inbox_,&event,0)!=pdTRUE)self.overflow_=true;
    }
    static void message_notice(void* context,std::uint64_t generation,const MessageNotice& notice) {
        auto& self=*static_cast<DeviceRuntime*>(context);Event event{};
        if(notice.topic.size()>=event.topic.size() || notice.data.size()>event.data.size()){self.overflow_=true;return;}
        event.generation=generation;event.message=notice.event;event.packet=notice.packet_id;event.qos=notice.qos;
        event.offset=notice.offset;event.total=notice.total;event.retained=notice.retained;
        event.topic_size=static_cast<int>(notice.topic.size());event.data_size=static_cast<int>(notice.data.size());
        if(!notice.topic.empty())std::memcpy(event.topic.data(),notice.topic.data(),notice.topic.size());
        if(!notice.data.empty())std::memcpy(event.data.data(),notice.data.data(),notice.data.size());
        if(xQueueSend(self.inbox_,&event,0)!=pdTRUE)self.overflow_=true;
    }
    bool start() {
        const auto now=monotonic_ms();
        if(!settings.ready() || !clock.trusted(utc_ms(),now) || !logic_.start(settings))return false;
        if(!storage_.initialize())return false;
        if(!stores_open_){if(!outgoing_.open() || !actions_.open())return false;stores_open_=true;}
        if(queue_.recover({settings.project.data(),settings.device.data()})!=QueueResult::Ok ||
           journal_.recover({settings.project.data(),settings.device.data()})!=JournalResult::Ok)return false;
        if(!wifi_.start(settings))return false;
        running=true;report_at_=now;retry_at_=now;wifi_retry_at_=now+1000;wifi_deadline_=now+30000;return true;
    }
    void disconnect() {
        auto old=live_;live_=0;ready_=false;subscription_=0;
        pump_.disconnected(old);frames_.disconnected(old);mqtt_.stop();xQueueReset(inbox_);
    }
    bool stop() {disconnect();wifi_.stop();running=false;const bool off=logic_.safe_off();if(!off)status("STATE GPIO_FAULT");return off;}
    void halt(const char* reason){stop();status(reason);}
    void retry(std::uint64_t now) {
        disconnect();if(!logic_.safe_off()){halt("STATE GPIO_FAULT");return;}
        retry_at_=now+mqtt_backoff_.next_delay_ms(esp_random());
    }
    bool message_id(std::uint64_t utc) {
        if(!wifi_.online())return false;
        std::array<std::uint8_t,10> entropy{};esp_fill_random(entropy.data(),entropy.size());
        return uuid7(utc,true,entropy,id_)==Error::Ok;
    }
    bool action(std::uint64_t utc,std::uint64_t now) {
        if(!clock.trusted(utc,now)){halt("STATE TIME_REQUIRED");return false;}
        if(overflow_){retry(now);return false;}
        std::uint64_t lease=0;auto decision=journal_.begin(request_,utc,true,lease,cached_);
        if(decision==JournalResult::Cached) {
            auto result=queue_.enqueue(cached_,utc,true);
            if(result!=QueueResult::Ok && result!=QueueResult::Full){halt("STATE STORAGE_FAULT");return false;}
            return true;
        }
        if(decision==JournalResult::Uncertain || decision==JournalResult::Blocked){status("STATE ACTION_UNCERTAIN");return true;}
        if(decision!=JournalResult::Execute) {
            if(decision==JournalResult::StorageFailure || decision==JournalResult::NotReady){halt("STATE STORAGE_FAULT");return false;}
            status("STATE ACTION_REJECTED");return true;
        }
        // A crash anywhere after begin leaves an uncertain intent, never automatic re-execution.
        if(!message_id(utc)){halt("STATE ID_FAILED");return false;}
        const auto result=logic_.execute(request_,output_);
        const char* error=result==ActionResult::Unsupported?"UNSUPPORTED_REQUEST":"GPIO_WRITE_FAILED";
        TerminalResult terminal{result==ActionResult::Success?ReplyStatus::Success:ReplyStatus::Failed,id_.data(),utc,true,
            result==ActionResult::Success?std::string_view(output_.data()):std::string_view("{}"),
            result==ActionResult::Success?std::string_view{}:std::string_view(error),
            result==ActionResult::Success?std::string_view{}:std::string_view("Device example rejected or failed the request")};
        if(journal_.complete(lease,terminal)!=JournalResult::Ok){halt("STATE STORAGE_FAULT");return false;}
        if(result==ActionResult::HardwareFailure){halt("STATE GPIO_FAULT");return false;}
        return true;
    }
};
}
extern "C" void app_main() {
    usb_serial_jtag_driver_config_t usb{.tx_buffer_size=2048,.rx_buffer_size=2048};
    if(usb_serial_jtag_driver_install(&usb)!=ESP_OK)return;
    if(esp_psram_get_size()!=8*1024*1024){status("ERR BOARD");return;}
    auto* memory=heap_caps_calloc(1,sizeof(DeviceRuntime),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT);
    if(!memory){status("ERR MEMORY");return;}
    auto* runtime=new(memory) DeviceRuntime;
    if(!runtime->initialize()){status("ERR MEMORY");return;}
    // No auto-run, auto-load, persisted time, unsafe fallback, flash erase, or eFuse writes.
    static std::array<char,DeviceSettings::max_document+16> line{};
    std::size_t length=0;bool discard=false;
    status("READY THINGSLINK USB1");
    for(;;) {
        std::uint8_t bytes[128];const int count=usb_serial_jtag_read_bytes(bytes,sizeof bytes,pdMS_TO_TICKS(20));
        for(int i=0;i<count;++i) {
            const char c=static_cast<char>(bytes[i]);
            if(c=='\n') {
                if(discard)status("ERR LINE");else {line[length]=0;runtime->command({line.data(),length});}
                volatile char* secret=line.data();for(std::size_t j=0;j<line.size();++j)secret[j]=0;
                length=0;discard=false;
            } else if(c=='\r')continue;
            else if(c==0 || length+1>=line.size()){discard=true;}
            else if(!discard)line[length++]=c;
            bytes[i]=0;
        }
        runtime->poll();
    }
}
