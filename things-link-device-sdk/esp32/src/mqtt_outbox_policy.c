// Use the fixed, unmodified Apache-2.0 ESP-MQTT outbox implementation, retaining
// its license headers. Only automatic retransmission selection is intercepted.
// Durability/retry budgets belong to our NVS Outbox, not this volatile buffer.
#include <stdint.h>
#include <stddef.h>
#include <inttypes.h>
#include "mqtt_config.h"
#undef CONFIG_MQTT_CUSTOM_OUTBOX
#define outbox_dequeue baijulink_vendor_outbox_dequeue
#include "../.local/esp-mqtt/lib/mqtt_outbox.c"
#undef outbox_dequeue

outbox_item_handle_t outbox_dequeue(outbox_handle_t box, pending_state_t state, outbox_tick_t* tick) {
    // Initial QUEUED delivery is allowed. TRANSMITTED/ACKNOWLEDGED must await
    // ACK/expiry, never silently repeat. This SDK has no MQTT5/QoS2/reconnect API.
    return state == QUEUED ? baijulink_vendor_outbox_dequeue(box, state, tick) : NULL;
}
