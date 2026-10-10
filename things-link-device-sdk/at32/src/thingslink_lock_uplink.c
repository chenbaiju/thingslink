#include "thingslink_lock_uplink.h"
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

typedef struct { char *data; size_t size; int failed; } json_writer;
static void append(json_writer *w, const char *format, ...)
{
    va_list args;
    int length;
    if (w->failed) return;
    va_start(args, format);
    length = vsnprintf(w->data + w->size, TL_LOCK_JSON_LIMIT - w->size, format, args);
    va_end(args);
    if (length < 0 || (size_t)length >= TL_LOCK_JSON_LIMIT - w->size) w->failed = 1;
    else w->size += (size_t)length;
}
static int uuid_valid(const char *id)
{
    unsigned i;
    if (!id) return 0;
    for (i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (id[i] != '-') return 0;
        } else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return 0;
    }
    return !id[36] && id[14] == '7' &&
        (id[19] == '8' || id[19] == '9' || id[19] == 'a' || id[19] == 'b');
}
static int version_valid(const char *v)
{
    unsigned i = 0, part;
    if (!v) return 0;
    for (part = 0; part < 3; ++part) {
        unsigned start = i;
        uint32_t value = 0;
        while (i < 32 && v[i] >= '0' && v[i] <= '9') {
            unsigned digit = (unsigned)(v[i]-'0');
            if (value > (65535u-digit)/10) return 0;
            value = value*10+digit; ++i;
        }
        if (start == i || (i - start > 1 && v[start] == '0')) return 0;
        if (part < 2) { if (i >= 32 || v[i++] != '.') return 0; }
    }
    return i <= 32 && !v[i];
}
static uint32_t little32(const unsigned char *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static unsigned char crc8(const unsigned char *p)
{
    unsigned i, bit;
    unsigned char crc = 0;
    for (i = 0; i < 15; ++i) {
        crc ^= p[i];
        for (bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ ((crc & 1) ? 0x8C : 0);
    }
    return crc;
}
static void format_time(uint32_t utc, char out[21])
{
    unsigned year = 1970, month = 1, days = utc / 86400, leap, n, i, values[6];
    static const unsigned char positions[] = {0,5,8,11,14,17};
    static const unsigned char lengths[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    for (;;) {
        leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        n = 365 + leap;
        if (days < n) break;
        days -= n; ++year;
    }
    for (;;) {
        n = lengths[month-1] + (month == 2 && leap);
        if (days < n) break;
        days -= n; ++month;
    }
    memcpy(out, "0000-00-00T00:00:00Z", 21);
    values[0] = year; values[1] = month; values[2] = days+1;
    values[3] = utc % 86400 / 3600; values[4] = utc % 3600 / 60; values[5] = utc % 60;
    for (i = 0; i < 6; ++i) {
        out[positions[i]+(i ? 0 : 2)] = (char)('0'+values[i]/10%10);
        out[positions[i]+(i ? 1 : 3)] = (char)('0'+values[i]%10);
    }
    out[0] = (char)('0'+year/1000%10); out[1] = (char)('0'+year/100%10);
}
int tl_lock_uplink_init(tl_lock_uplink *ctx, const char *version,
                         tl_lock_id_provider next, void *id_context)
{
    if (!ctx || !next || !version_valid(version)) return TL_LOCK_INVALID;
    memset(ctx, 0, sizeof(*ctx)); strcpy(ctx->model_version, version);
    ctx->next_id = next; ctx->id_context = id_context; return 0;
}
/* 缓存满时明确背压；来源的结束/持久保存不能靠自动淘汰猜测。 */
static int source_slot(tl_lock_uplink *ctx, const char *id)
{
    unsigned i;
    int empty = TL_LOCK_FULL;
    for (i = 0; i < TL_LOCK_SOURCE_LIMIT; ++i) {
        if (ctx->sources[i].used && !strcmp(ctx->sources[i].source_id, id)) return (int)i;
        if (!ctx->sources[i].used && empty == TL_LOCK_FULL) empty = (int)i;
    }
    return empty;
}
static int new_message(tl_lock_uplink *ctx, tl_lock_source *source,
                       tl_lock_batch *batch, unsigned kind, json_writer *w)
{
    unsigned i, j;
    char id[37] = {0}, utc[21];
    tl_lock_message *message;
    if (batch->count >= TL_LOCK_BATCH_LIMIT || source->id_count >= 4 ||
        ctx->next_id(ctx->id_context, id) || !uuid_valid(id) || !strcmp(id, source->source_id))
        return TL_LOCK_INVALID;
    for (i = 0; i < TL_LOCK_SOURCE_LIMIT; ++i) {
        if (!ctx->sources[i].used) continue;
        if (!strcmp(id, ctx->sources[i].source_id)) return TL_LOCK_CONFLICT;
        for (j = 0; j < ctx->sources[i].id_count; ++j)
            if (!strcmp(id, ctx->sources[i].message_ids[j])) return TL_LOCK_CONFLICT;
    }
    for (j = 0; j < source->id_count; ++j)
        if (!strcmp(id, source->message_ids[j])) return TL_LOCK_CONFLICT;
    strcpy(source->message_ids[source->id_count++], id);
    message = &batch->messages[batch->count++]; message->kind = kind;
    w->data = message->json; w->size = 0; w->failed = 0;
    format_time(source->utc, utc);
    append(w, "{\"messageId\":\"%s\",", id);
    if (kind != TL_MQTT_PROPERTY) append(w, "\"modelVersion\":\"%s\",", ctx->model_version);
    append(w, "\"occurredAt\":\"%s\",\"%s\":{", utc, kind == TL_MQTT_PROPERTY ? "payload" : "params");
    if (kind != TL_MQTT_PROPERTY) append(w, "\"sourceRecordId\":\"%s\",", source->source_id);
    append(w, "\"doorOpen\":%s", source->door ? "true" : "false");
    return 0;
}
static int finish_message(tl_lock_batch *batch, json_writer *w)
{
    append(w, "}}");
    if (w->failed) return TL_LOCK_INVALID;
    batch->messages[batch->count-1].size = w->size; return 0;
}
static void record_params(json_writer *w, unsigned type, uint32_t count, unsigned ble, int event)
{
    append(w, ",\"unlockCount\":%lu,\"bleConnected\":%s", (unsigned long)count, ble ? "true" : "false");
    if (event) append(w, ",\"rawRecordType\":%u", type);
}
static int prepare(tl_lock_uplink *ctx, const char *id, uint32_t utc, unsigned door,
                     tl_lock_batch *batch, tl_lock_source *source)
{
    int slot;
    if (!batch) return TL_LOCK_INVALID;
    memset(batch, 0, sizeof(*batch));
    if (!ctx || !ctx->next_id || !uuid_valid(id) || door > 1) return TL_LOCK_INVALID;
    if (!utc) return TL_LOCK_TIME_UNKNOWN;
    slot = source_slot(ctx, id);
    if (slot < 0) return slot;
    *source = ctx->sources[slot];
    if (source->used && (source->utc != utc || source->door != door)) return TL_LOCK_CONFLICT;
    if (!source->used) {
        memset(source, 0, sizeof(*source)); source->used = 1;
        strcpy(source->source_id, id); source->utc = utc; source->door = door;
    }
    return slot;
}
static int changed(const tl_lock_uplink *ctx, const tl_lock_source *source)
{
    return ctx->door_known && source->utc >= ctx->door_utc && ctx->door != source->door;
}
static void commit(tl_lock_uplink *ctx, const tl_lock_source *source, int slot)
{
    ctx->sources[slot] = *source;
    if (!ctx->door_known || source->utc >= ctx->door_utc) {
        ctx->door_known = 1; ctx->door = source->door; ctx->door_utc = source->utc;
    }
}
int tl_lock_uplink_sensor(tl_lock_uplink *ctx, const char *id, uint32_t utc,
                           unsigned door, tl_lock_batch *batch)
{
    tl_lock_source source;
    json_writer w;
    int result = prepare(ctx, id, utc, door, batch, &source);
    int slot = result;
    if (result < 0) return result;
    if (source.sensor_seen || source.record_seen) return 0;
    if (!ctx->door_known || changed(ctx, &source)) {
        result = new_message(ctx, &source, batch, TL_MQTT_PROPERTY, &w);
        if (!result) result = finish_message(batch, &w);
        if (!result && changed(ctx, &source)) {
            result = new_message(ctx, &source, batch, TL_MQTT_DOOR_CHANGED, &w);
            if (!result) result = finish_message(batch, &w);
        }
    }
    if (result < 0) { batch->count = 0; return result; }
    source.sensor_seen = 1; commit(ctx, &source, slot); return (int)batch->count;
}
int tl_lock_uplink_record(tl_lock_uplink *ctx, const char *id, const unsigned char *raw,
                           size_t size, unsigned valid, tl_lock_batch *batch)
{
    static const char *const methods[] = {"DYNAMIC_PASSWORD", "REMOTE", "MF_CARD", NULL, "LOCAL_PASSWORD"};
    tl_lock_source source;
    json_writer w;
    uint32_t utc, count;
    unsigned status, type, door, ble, kind = 0;
    int result, slot;
    if (batch) memset(batch, 0, sizeof(*batch));
    if (!raw || size != 16 || valid & ~(TL_LOCK_PASSWORD_VALID | TL_LOCK_CARD_VALID) || crc8(raw) != raw[15])
        return TL_LOCK_INVALID;
    utc = little32(raw); count = little32(raw+4);
    status = (unsigned)raw[8] | (unsigned)raw[9] << 8;
    type = status >> 1 & 15; door = status & 1; ble = status >> 9 & 1;
    result = prepare(ctx, id, utc, door, batch, &source); slot = result;
    if (result < 0) return result;
    if (source.record_seen) {
        return !memcmp(source.raw, raw, 16) && source.valid_fields == valid ? 0 : TL_LOCK_CONFLICT;
    }
    memcpy(source.raw, raw, 16); source.record_seen = 1; source.valid_fields = valid;
    result = new_message(ctx, &source, batch, TL_MQTT_PROPERTY, &w);
    if (!result) {
        record_params(&w, type, count, ble, 0);
        if (type == 15 && door) append(&w, ",\"abnormalOpenNotice\":1");
        if (type == 13) append(&w, ",\"passwordTrialNotice\":1");
        result = finish_message(batch, &w);
    }
    if (!result && !source.sensor_seen && changed(ctx, &source)) {
        result = new_message(ctx, &source, batch, TL_MQTT_DOOR_CHANGED, &w);
        if (!result) { record_params(&w, type, count, ble, 1); result = finish_message(batch, &w); }
    }
    if (type == 10 || type == 11 || type == 12 || type == 14) kind = TL_MQTT_UNLOCK_RECORD;
    else if (type == 15 && door) kind = TL_MQTT_ABNORMAL_OPEN;
    else if (type == 13) kind = TL_MQTT_PASSWORD_TRIAL;
    if (!result && kind) {
        result = new_message(ctx, &source, batch, kind, &w);
        if (!result) {
            record_params(&w, type, count, ble, 1);
            if (kind == TL_MQTT_UNLOCK_RECORD) {
                append(&w, ",\"method\":\"%s\"", methods[type-10]);
                if ((type == 10 || type == 14) && (valid & TL_LOCK_PASSWORD_VALID))
                    append(&w, ",\"passwordIndex\":%u", (unsigned)raw[10]);
                if (type == 12 && (valid & TL_LOCK_CARD_VALID))
                    append(&w, ",\"cardUid\":\"%08lX\"", (unsigned long)little32(raw+11));
            }
            result = finish_message(batch, &w);
        }
    }
    if (result < 0) { batch->count = 0; return result; }
    commit(ctx, &source, slot); return (int)batch->count;
}
int tl_lock_message_encode(const tl_lock_message *message, unsigned char *buffer,
                            size_t capacity, const char *project, const char *device,
                            uint16_t packet_id, int duplicate)
{
    if (!message || message->size >= sizeof(message->json)) return TL_LOCK_INVALID;
    return tl_mqtt_publish(buffer, capacity, project, device, message->kind,
                           message->json, message->size, packet_id, duplicate);
}
