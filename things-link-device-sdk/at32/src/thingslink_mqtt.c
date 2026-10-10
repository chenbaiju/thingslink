#include "thingslink_mqtt.h"
#include <stdio.h>
#include <string.h>

typedef struct {
    unsigned char *data;
    size_t capacity;
    size_t size;
    int failed;
} writer;

static size_t bounded_length(const char *text, size_t maximum)
{
    size_t length;
    if (text == NULL) return maximum + 1;
    for (length = 0; length <= maximum; ++length)
        if (text[length] == '\0') return length;
    return maximum + 1;
}

static int valid_key(const char *key)
{
    size_t i, length = bounded_length(key, 64);
    if (length == 0 || length > 64) return 0;
    for (i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)key[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9')) continue;
        if (i > 0 && (c == '_' || c == '-')) continue;
        return 0;
    }
    return 1;
}

static int valid_id(const char *id)
{
    size_t i;
    if (bounded_length(id, 36) != 36 || id[14] != '7' ||
        !(id[19] == '8' || id[19] == '9' || id[19] == 'a' || id[19] == 'b'))
        return 0;
    for (i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (id[i] != '-') return 0;
        } else if (!((id[i] >= '0' && id[i] <= '9') ||
                     (id[i] >= 'a' && id[i] <= 'f'))) return 0;
    }
    return 1;
}

int tl_mqtt_valid_utc(const char *text)
{
    unsigned i, year, month, day, hour, minute, second, maximum;
    static const unsigned char days[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (bounded_length(text, 20) != 20 || text[4] != '-' || text[7] != '-' ||
        text[10] != 'T' || text[13] != ':' || text[16] != ':' || text[19] != 'Z')
        return 0;
    for (i = 0; i < 19; ++i) {
        if (i == 4 || i == 7 || i == 10 || i == 13 || i == 16) continue;
        if (text[i] < '0' || text[i] > '9') return 0;
    }
    year = (text[0]-'0')*1000+(text[1]-'0')*100+(text[2]-'0')*10+text[3]-'0';
    month = (text[5]-'0')*10+text[6]-'0';
    day = (text[8]-'0')*10+text[9]-'0';
    hour = (text[11]-'0')*10+text[12]-'0';
    minute = (text[14]-'0')*10+text[15]-'0';
    second = (text[17]-'0')*10+text[18]-'0';
    if (year < 1970 || month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59)
        return 0;
    maximum = days[month-1];
    if (month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ++maximum;
    return day >= 1 && day <= maximum;
}

static void bytes(writer *w, const void *data, size_t length)
{
    if (w->failed || length > w->capacity - w->size) { w->failed = 1; return; }
    memcpy(w->data + w->size, data, length);
    w->size += length;
}

static void number(writer *w, uint16_t value)
{
    unsigned char data[2] = {(unsigned char)(value >> 8), (unsigned char)value};
    bytes(w, data, 2);
}

static void string(writer *w, const char *value)
{
    size_t length = strlen(value);
    number(w, (uint16_t)length);
    bytes(w, value, length);
}

static int finish(writer *w, unsigned char header)
{
    unsigned char encoded[4];
    size_t remaining, count = 0, value;
    if (w->failed) return -1;
    remaining = value = w->size;
    do {
        encoded[count] = (unsigned char)(value % 128);
        value /= 128;
        if (value) encoded[count] |= 0x80;
        ++count;
    } while (value && count < sizeof(encoded));
    if (value || remaining + count + 1 > w->capacity) return -1;
    memmove(w->data + count + 1, w->data, remaining);
    w->data[0] = header;
    memcpy(w->data + 1, encoded, count);
    return (int)(remaining + count + 1);
}

int tl_mqtt_connect(unsigned char *buffer, size_t capacity, const char *project,
                    const char *device, const char *client_id, const char *token)
{
    writer w = {buffer, capacity, 0, 0};
    char username[130];
    static const unsigned char protocol[] = {0,4,'M','Q','T','T',4,0xC2,0,30};
    size_t length, i;
    if (buffer == NULL || !valid_key(project) || !valid_key(device)) return -1;
    length = bounded_length(client_id, 64);
    if (length == 0 || length > 64 || bounded_length(token, 64) != 64) return -1;
    /* 当前平台生成64位十六进制设备密钥；不接受截断或隐式缩短。 */
    for (i = 0; i < 64; ++i)
        if (!((token[i] >= '0' && token[i] <= '9') ||
              (token[i] >= 'a' && token[i] <= 'f'))) return -1;
    for (i = 0; i < length; ++i)
        if ((unsigned char)client_id[i] < 0x21 || (unsigned char)client_id[i] > 0x7E)
            return -1;
    snprintf(username, sizeof(username), "%s/%s", project, device);
    bytes(&w, protocol, sizeof(protocol));
    string(&w, client_id);
    string(&w, username);
    string(&w, token);
    return finish(&w, 0x10);
}

int tl_mqtt_subscribe(unsigned char *buffer, size_t capacity, const char *project,
                      const char *device, uint16_t packet_id)
{
    writer w = {buffer, capacity, 0, 0};
    char topic[147];
    unsigned char qos = 1;
    if (buffer == NULL || packet_id == 0 || !valid_key(project) || !valid_key(device))
        return -1;
    snprintf(topic, sizeof(topic), "tc/v1/%s/%s/down/#", project, device);
    number(&w, packet_id);
    string(&w, topic);
    bytes(&w, &qos, 1);
    return finish(&w, 0x82);
}

int tl_mqtt_online(unsigned char *buffer, size_t capacity, const char *project,
                   const char *device, const char *message_id, const char *utc,
                   uint16_t packet_id, int duplicate)
{
    writer w = {buffer, capacity, 0, 0};
    char topic[161], payload[130];
    int length;
    if (buffer == NULL || packet_id == 0 || !valid_key(project) || !valid_key(device) ||
        !valid_id(message_id) || !tl_mqtt_valid_utc(utc)) return -1;
    snprintf(topic, sizeof(topic), "tc/v1/%s/%s/up/property/report", project, device);
    length = snprintf(payload, sizeof(payload),
                      "{\"messageId\":\"%s\",\"occurredAt\":\"%s\",\"payload\":{\"online\":true}}",
                      message_id, utc);
    if (length < 0 || (size_t)length >= sizeof(payload)) return -1;
    string(&w, topic);
    number(&w, packet_id);
    bytes(&w, payload, (size_t)length);
    /* QoS1且不retained；重发只改变DUP位，业务ID和负载由调用者保留。 */
    return finish(&w, duplicate ? 0x3A : 0x32);
}

int tl_mqtt_publish(unsigned char *buffer, size_t capacity, const char *project,
                     const char *device, unsigned kind, const char *json,
                     size_t length, uint16_t packet_id, int duplicate)
{
    static const char *const suffixes[] = {
        "property/report", "event/doorChanged", "event/unlockRecord",
        "event/abnormalOpen", "event/passwordTrialLimit"
    };
    writer w = {buffer, capacity, 0, 0};
    char topic[176];
    int size;
    if (!buffer || !json || !packet_id || kind >= sizeof(suffixes)/sizeof(suffixes[0]) ||
        length < 2 || length >= 512 || json[0] != '{' || json[length-1] != '}' ||
        memchr(json, 0, length) || !valid_key(project) || !valid_key(device)) return -1;
    size = snprintf(topic, sizeof(topic), "tc/v1/%s/%s/up/%s", project, device, suffixes[kind]);
    if (size < 0 || (size_t)size >= sizeof(topic)) return -1;
    string(&w, topic); number(&w, packet_id); bytes(&w, json, length);
    return finish(&w, duplicate ? 0x3A : 0x32);
}

int tl_mqtt_command_reply(unsigned char *buffer, size_t capacity, const char *project,
                          const char *device, const char *command_id, const char *json,
                          size_t length, uint16_t packet_id, int duplicate)
{
    writer w = {buffer, capacity, 0, 0};
    char topic[200];
    int result;
    if (!buffer || !json || !packet_id || !valid_key(project) || !valid_key(device) ||
        !valid_id(command_id) || length < 2 || length >= 512 || json[0] != '{' ||
        json[length-1] != '}' || memchr(json, 0, length)) return -1;
    result = snprintf(topic, sizeof(topic), "tc/v1/%s/%s/up/command/%s/reply", project, device, command_id);
    if (result <= 0 || (size_t)result >= sizeof(topic)) return -1;
    string(&w, topic); number(&w, packet_id); bytes(&w, json, length);
    return finish(&w, duplicate ? 0x3A : 0x32);
}

int tl_mqtt_frame(const unsigned char *data, size_t length, size_t limit, size_t *total)
{
    size_t position, remaining = 0, multiplier = 1;
    unsigned char digit;
    if (data == NULL || total == NULL || limit < 2) return -1;
    if (length < 2) return 0;
    if ((data[0] >> 4) == 0 || (data[0] >> 4) == 15) return -1;
    for (position = 1; position <= 4; ++position) {
        if (position >= length) return 0;
        digit = data[position];
        remaining += (digit & 0x7F) * multiplier;
        if (remaining > limit || position + 1 > limit - remaining) return -1;
        if (!(digit & 0x80)) {
            /* 拒绝非最短长度编码，避免同一帧有多种边界解释。 */
            if (position > 1 && digit == 0) return -1;
            *total = remaining + position + 1;
            return length >= *total ? 1 : 0;
        }
        if (position == 4) return -1;
        multiplier *= 128;
    }
    return -1;
}
