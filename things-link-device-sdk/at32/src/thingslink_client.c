#include "thingslink_client.h"
#include "thingslink_bearssl_profile.h"
#include "thingslink_mqtt.h"
#include "thingslink_command.h"
#include <stdio.h>
#include <string.h>

static br_ssl_client_context client;
static br_x509_minimal_context certificates;
static br_hmac_drbg_context random_source;
static br_x509_trust_anchor anchor;
static unsigned char tls_buffer[2048], tx[768], rx[512], report[768];
static tl_test_config *config;
static size_t tx_size, tx_offset, rx_size, report_size;
static uint32_t clock_last, last_io, last_report, stage_start, ping_start;
static uint64_t elapsed_ms;
static unsigned mqtt_phase, initialized, waiting_ping, report_sent;
static uint16_t report_id;
static tl_command_cache commands;
static unsigned diagnostic_enabled, door_known, door_open;
static unsigned reply_queue[8], reply_count;
static uint16_t inbound_ack[8];
static unsigned ack_count;
volatile tl_status tl_diagnostics;

static void clear_secret(void *data, size_t size)
{
    volatile unsigned char *p = data;
    while (size--) *p++ = 0;
}
static uint64_t unix_ms(uint32_t ticks)
{
    elapsed_ms += (uint32_t)(ticks - clock_last); clock_last = ticks;
    return (uint64_t)config->utc * 1000 + elapsed_ms;
}
static int terminated(const char *text, size_t size)
{
    return memchr(text, 0, size) != NULL;
}
int tl_client_init(tl_test_config *value, uint32_t ticks)
{
    size_t i;
    unsigned entropy_nonzero = 0;
    if (!value || value->ready != TL_TEST_CONFIG_READY || value->version != 1 ||
        value->utc < 1704067200u || value->utc > 4102444799u || !value->port ||
        !value->dn_size || value->dn_size > sizeof(value->ca_dn) ||
        value->ca_point[0] != 4 || !value->hostname[0] ||
        !terminated(value->hostname, 65) || !terminated(value->project, 65) ||
        !terminated(value->device, 65) || !terminated(value->client_id, 65) ||
        !terminated(value->token, 65)) return -1;
    for (i = 0; i < sizeof(value->entropy); ++i) entropy_nonzero |= value->entropy[i];
    if (!entropy_nonzero) return -1;
    /* 先验证身份报文，失败不启动网络，不记录密钥。 */
    if (tl_mqtt_connect(tx, sizeof(tx), value->project, value->device,
                        value->client_id, value->token) < 0) return -1;
    clear_secret(tx, sizeof(tx));
    config = value; clock_last = ticks; elapsed_ms = 0;
    br_hmac_drbg_init(&random_source, &br_sha256_vtable,
                     value->entropy, sizeof(value->entropy));
    clear_secret(value->entropy, sizeof(value->entropy));
    memset(&anchor, 0, sizeof(anchor));
    anchor.dn.data = value->ca_dn; anchor.dn.len = value->dn_size;
    anchor.flags = BR_X509_TA_CA; anchor.pkey.key_type = BR_KEYTYPE_EC;
    anchor.pkey.key.ec.curve = BR_EC_secp256r1;
    anchor.pkey.key.ec.q = value->ca_point; anchor.pkey.key.ec.qlen = 65;
    initialized = 1; report_id = 10; report_size = report_sent = 0;
    diagnostic_enabled = door_known = door_open = reply_count = ack_count = 0;
    tl_command_init(&commands);
    tl_diagnostics.phase = 1; return 0;
}
int tl_client_connected(uint32_t ticks)
{
    unsigned char seed[32];
    uint64_t utc;
    if (!initialized) return -1;
    utc = unix_ms(ticks) / 1000;
    tl_bearssl_profile(&client, &certificates, &anchor, 1);
    br_x509_minimal_set_time(&certificates, (uint32_t)(utc / 86400 + 719528),
                            (uint32_t)(utc % 86400));
    br_ssl_engine_set_buffer(&client.eng, tls_buffer, sizeof(tls_buffer), 0);
    br_hmac_drbg_generate(&random_source, seed, sizeof(seed));
    br_ssl_engine_inject_entropy(&client.eng, seed, sizeof(seed)); clear_secret(seed, sizeof(seed));
    if (!br_ssl_client_reset(&client, config->hostname, 0)) return -1;
    tx_size = tx_offset = rx_size = mqtt_phase = waiting_ping = 0;
    ack_count = 0;
    stage_start = last_io = ticks; tl_diagnostics.phase = 2; return 0;
}
void tl_client_disconnected(void)
{
    tx_size = tx_offset = rx_size = 0;
    clear_secret(tx, sizeof(tx)); clear_secret(tls_buffer, sizeof(tls_buffer));
    /* 单个业务尝试保留原上报ID/时间/负载，重连仅设置DUP。 */
    tl_diagnostics.phase = 1; ++tl_diagnostics.reconnects;
}
static int queue(int length)
{
    if (length <= 0) return -1;
    tx_size = (size_t)length; tx_offset = 0; return 0;
}
static void format_utc(uint64_t seconds, char out[21])
{
    unsigned year = 1970, month = 1, day, days, leap, count, i;
    unsigned values[6];
    static const unsigned char positions[] = {0,5,8,11,14,17};
    static const unsigned char months[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    days = (unsigned)(seconds / 86400);
    for (;;) {
        leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        count = 365 + leap;
        if (days < count) break;
        days -= count; ++year;
    }
    for (;;) {
        count = months[month-1] + (month == 2 && leap);
        if (days < count) break;
        days -= count; ++month;
    }
    day = days + 1; seconds %= 86400;
    memcpy(out, "0000-00-00T00:00:00Z", 21);
    values[0] = year; values[1] = month; values[2] = day;
    values[3] = (unsigned)(seconds / 3600);
    values[4] = (unsigned)(seconds / 60 % 60); values[5] = (unsigned)(seconds % 60);
    for (i = 0; i < 6; ++i) {
        out[positions[i] + (i ? 0 : 2)] = (char)('0' + values[i] / 10 % 10);
        out[positions[i] + (i ? 1 : 3)] = (char)('0' + values[i] % 10);
    }
    out[0] = (char)('0' + year / 1000 % 10); out[1] = (char)('0' + year / 100 % 10);
}
int tl_client_next_id(void *unused, char id[37])
{
    static const char hex[] = "0123456789abcdef";
    unsigned char uuid[16];
    uint64_t timestamp;
    unsigned i, pos = 0;
    (void)unused;
    if (!initialized || !id) return -1;
    timestamp = unix_ms(clock_last);
    br_hmac_drbg_generate(&random_source, uuid, sizeof(uuid));
    for (i = 0; i < 6; ++i) uuid[i] = (unsigned char)(timestamp >> (40 - i*8));
    uuid[6] = (uuid[6] & 15) | 0x70; uuid[8] = (uuid[8] & 63) | 0x80;
    for (i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) id[pos++] = '-';
        id[pos++] = hex[uuid[i] >> 4]; id[pos++] = hex[uuid[i] & 15];
    }
    id[pos] = 0; return 0;
}
int tl_client_submit_lock(const tl_lock_message *message)
{
    int length;
    uint16_t next;
    if (!initialized || !message) return -1;
    if (report_size) return 0;
    next = (uint16_t)(report_id + 1); if (!next) next = 10;
    length = tl_lock_message_encode(message, report, sizeof(report), config->project,
                                    config->device, next, 0);
    if (length < 0) return -1;
    report_id = next; report_size = (size_t)length; report_sent = 0; return 1;
}
int tl_client_enable_door_diagnostic(void)
{
    if (!initialized) return -1;
    diagnostic_enabled = 1; return 0;
}
int tl_client_set_door_state(unsigned known, unsigned door)
{
    if (!initialized || !diagnostic_enabled || known > 1 || door > 1) return -1;
    door_known = known; door_open = door; return 0;
}
static int online(uint32_t ticks)
{
    char id[37], utc[21];
    int length;
    if (report_size) {
        memcpy(tx, report, report_size); if (report_sent) tx[0] |= 8;
        report_sent = 1;
        return queue((int)report_size);
    }
    if (tl_client_next_id(NULL, id)) return -1;
    format_utc(unix_ms(ticks) / 1000, utc);
    if (++report_id == 0) report_id = 10;
    length = tl_mqtt_online(report, sizeof(report), config->project, config->device,
                                id, utc, report_id, 0);
    if (length < 0) return -1;
    report_size = (size_t)length; report_sent = 1;
    memcpy(tx, report, report_size); return queue(length);
}
static int packet(const unsigned char *data, size_t size, uint32_t ticks)
{
    size_t header = 2;
    while (header <= size && (data[header-1] & 128)) ++header;
    if (header > size) return -1;
    if (data[0] == 0x20 && mqtt_phase == 1) {
        if (size != 4 || data[2] || data[3]) return -1;
        mqtt_phase = 2; stage_start = ticks; return 0;
    }
    if (data[0] == 0x90 && mqtt_phase == 3) {
        if (size != 5 || data[2] || data[3] != 2 || data[4] != 1) return -1;
        mqtt_phase = 4; stage_start = ticks; return 0;
    }
    if (data[0] == 0x40 && mqtt_phase == 5) {
        if (size != 4 || ((unsigned)data[2] << 8 | data[3]) != report_id) return -1;
        report_size = report_sent = 0; mqtt_phase = 6; last_report = ticks;
        ++tl_diagnostics.reports_acked; tl_diagnostics.phase = 4; return 0;
    }
    if (data[0] == 0xD0 && size == 2 && waiting_ping) { waiting_ping = 0; return 0; }
    if ((data[0] >> 4) == 3 && diagnostic_enabled && mqtt_phase >= 4) {
        char topic[200], utc[21];
        size_t length, offset;
        uint16_t incoming;
        unsigned i;
        int result;
        /* 下行限定QoS1、非retained；传输确认与业务终态分开排队。 */
        if ((data[0] & 7) != 2 || size-header < 4 || ack_count == 8) return -1;
        length = (size_t)data[header] << 8 | data[header+1]; offset = header+2;
        if (!length || length >= sizeof(topic) || length+2 > size-offset || memchr(data+offset, 0, length)) return -1;
        memcpy(topic, data+offset, length); topic[length] = 0; offset += length;
        incoming = (uint16_t)((unsigned)data[offset] << 8 | data[offset+1]); offset += 2;
        if (!incoming) return -1;
        inbound_ack[ack_count++] = incoming;
        format_utc(unix_ms(ticks) / 1000, utc);
        result = tl_command_read(&commands, config->project, config->device, topic,
            (const char *)data+offset, size-offset, door_known, door_open, utc, tl_client_next_id, NULL);
        /* 无可关联身份、错误目标、冲突或满缓存均不伪造业务成功。 */
        if (result < 0) return 0;
        for (i = 0; i < reply_count; ++i) if (reply_queue[i] == (unsigned)result-1) return 0;
        if (reply_count < 8) reply_queue[reply_count++] = (unsigned)result-1;
        return 0;
    }
    return -1;
}
int tl_client_poll(const tl_transport *transport, uint32_t ticks)
{
    size_t size, frame;
    unsigned state;
    unsigned char *data;
    int count, parsed;
    unix_ms(ticks);
    state = br_ssl_engine_current_state(&client.eng);
    if (state & BR_SSL_CLOSED) {
        tl_diagnostics.tls_error = br_ssl_engine_last_error(&client.eng); return -1;
    }
    if ((mqtt_phase < 6 && (uint32_t)(ticks-stage_start) > 30000) ||
        (waiting_ping && (uint32_t)(ticks-ping_start) > 10000)) return -1;
    if (state & BR_SSL_SENDREC) {
        data = br_ssl_engine_sendrec_buf(&client.eng, &size);
        count = transport->send(data, size);
        if (count < 0 || (size_t)count > size) return -1;
        if (count) { br_ssl_engine_sendrec_ack(&client.eng, (size_t)count); last_io = ticks; }
    }
    state = br_ssl_engine_current_state(&client.eng);
    if (state & BR_SSL_RECVREC) {
        data = br_ssl_engine_recvrec_buf(&client.eng, &size);
        count = transport->receive(data, size);
        if (count < 0 || (size_t)count > size) return -1;
        if (count) { br_ssl_engine_recvrec_ack(&client.eng, (size_t)count); last_io = ticks; }
    }
    state = br_ssl_engine_current_state(&client.eng);
    if (state & BR_SSL_RECVAPP) {
        data = br_ssl_engine_recvapp_buf(&client.eng, &size);
        if (size > sizeof(rx)-rx_size) size = sizeof(rx)-rx_size;
        if (!size) return -1;
        memcpy(rx+rx_size, data, size); rx_size += size;
        br_ssl_engine_recvapp_ack(&client.eng, size);
        while ((parsed = tl_mqtt_frame(rx, rx_size, sizeof(rx), &frame)) == 1) {
            if (packet(rx, frame, ticks)) { ++tl_diagnostics.mqtt_error; return -1; }
            rx_size -= frame; memmove(rx, rx+frame, rx_size);
        }
        if (parsed < 0) { ++tl_diagnostics.mqtt_error; return -1; }
    }
    state = br_ssl_engine_current_state(&client.eng);
    if (state & BR_SSL_SENDAPP) {
        if (!tx_size) {
            if (ack_count) {
                tx[0] = 0x40; tx[1] = 2; tx[2] = (unsigned char)(inbound_ack[0] >> 8);
                tx[3] = (unsigned char)inbound_ack[0];
                --ack_count; memmove(inbound_ack, inbound_ack+1, ack_count*sizeof(inbound_ack[0])); queue(4);
            } else if (!mqtt_phase) {
                if (queue(tl_mqtt_connect(tx, sizeof(tx), config->project, config->device,
                                         config->client_id, config->token))) return -1;
                mqtt_phase = 1; tl_diagnostics.phase = 3; stage_start = ticks;
            } else if (mqtt_phase == 2) {
                if (queue(tl_mqtt_subscribe(tx, sizeof(tx), config->project, config->device, 2))) return -1;
                mqtt_phase = 3; stage_start = ticks;
            } else if ((mqtt_phase == 4 || mqtt_phase == 6) && !report_size && reply_count) {
                int length;
                if (++report_id == 0) report_id = 10;
                length = tl_command_reply_encode(&commands.replies[reply_queue[0]], report, sizeof(report),
                    config->project, config->device, report_id, 0);
                if (length < 0) return -1;
                --reply_count; memmove(reply_queue, reply_queue+1, reply_count*sizeof(reply_queue[0]));
                report_size = (size_t)length; report_sent = 0;
                if (online(ticks)) return -1;
                mqtt_phase = 5; stage_start = ticks;
            } else if (mqtt_phase == 4 || (mqtt_phase == 6 &&
                       (report_size || (uint32_t)(ticks-last_report) >= 30000))) {
                if (online(ticks)) return -1;
                mqtt_phase = 5; stage_start = ticks;
            } else if (!waiting_ping && (uint32_t)(ticks-last_io) >= 10000) {
                tx[0] = 0xC0; tx[1] = 0; queue(2); waiting_ping = 1; ping_start = ticks;
            }
        }
        if (tx_size) {
            data = br_ssl_engine_sendapp_buf(&client.eng, &size);
            if (size > tx_size-tx_offset) size = tx_size-tx_offset;
            memcpy(data, tx+tx_offset, size); tx_offset += size;
            br_ssl_engine_sendapp_ack(&client.eng, size);
            if (tx_offset == tx_size) {
                tx_size = tx_offset = 0; clear_secret(tx, sizeof(tx));
                br_ssl_engine_flush(&client.eng, 0);
            }
        }
    }
    return 0;
}
