#include "thingslink_command.h"
#include <stdio.h>
#include <string.h>

/* 有界JSON子集：字段名/身份为未转义ASCII；拒绝重复键、尾随字节及过深嵌套。 */
typedef struct { size_t start, end; unsigned next, children; char type; } token;
typedef struct { const char *data; size_t size, pos; unsigned count; token nodes[64]; } parser;
static void space(parser *p)
{
    while (p->pos < p->size && (p->data[p->pos] == ' ' || p->data[p->pos] == '\t' ||
           p->data[p->pos] == '\r' || p->data[p->pos] == '\n')) ++p->pos;
}
static int same(parser *p, unsigned a, unsigned b)
{
    token *x = &p->nodes[a], *y = &p->nodes[b];
    return x->end-x->start == y->end-y->start &&
        !memcmp(p->data+x->start, p->data+y->start, x->end-x->start);
}
static int value(parser *p, unsigned depth)
{
    unsigned n, child, k;
    char c, close;
    token *t;
    space(p);
    if (p->pos == p->size || depth > 8 || p->count == 64) return -1;
    n = p->count++; t = &p->nodes[n]; memset(t, 0, sizeof(*t));
    t->start = p->pos; c = p->data[p->pos++]; t->type = c;
    if (c == '"') {
        while (p->pos < p->size && p->data[p->pos] != '"') {
            c = p->data[p->pos++];
            if ((unsigned char)c < 32 || (unsigned char)c > 126 || c == '\\') return -1;
        }
        if (p->pos == p->size) return -1;
        ++p->pos;
    } else if (c == '{' || c == '[') {
        close = c == '{' ? '}' : ']'; space(p);
        if (p->pos < p->size && p->data[p->pos] == close) ++p->pos;
        else for (;;) {
            child = p->count;
            if (value(p, depth+1) < 0) return -1;
            if (t->type == '{') {
                if (p->nodes[child].type != '"') return -1;
                for (k = n+1; k < child; k = p->nodes[p->nodes[k].next].next)
                    if (same(p, k, child)) return -1;
                space(p);
                if (p->pos == p->size || p->data[p->pos++] != ':' || value(p, depth+1) < 0) return -1;
            }
            ++t->children; space(p);
            if (p->pos == p->size) return -1;
            c = p->data[p->pos++];
            if (c == close) break;
            if (c != ',') return -1;
        }
    } else if (c == 't' || c == 'f' || c == 'n') {
        const char *literal = c == 't' ? "true" : c == 'f' ? "false" : "null";
        size_t length = strlen(literal);
        if (length > p->size-t->start || memcmp(p->data+t->start, literal, length)) return -1;
        p->pos = t->start + length;
    } else {
        t->type = 'N'; p->pos = t->start;
        if (p->data[p->pos] == '-') ++p->pos;
        if (p->pos == p->size || p->data[p->pos] < '0' || p->data[p->pos] > '9') return -1;
        if (p->data[p->pos++] != '0')
            while (p->pos < p->size && p->data[p->pos] >= '0' && p->data[p->pos] <= '9') ++p->pos;
        if (p->pos < p->size && p->data[p->pos] == '.') {
            ++p->pos; k = (unsigned)p->pos;
            while (p->pos < p->size && p->data[p->pos] >= '0' && p->data[p->pos] <= '9') ++p->pos;
            if (p->pos == k) return -1;
        }
        if (p->pos < p->size && (p->data[p->pos] == 'e' || p->data[p->pos] == 'E')) {
            ++p->pos;
            if (p->pos < p->size && (p->data[p->pos] == '+' || p->data[p->pos] == '-')) ++p->pos;
            k = (unsigned)p->pos;
            while (p->pos < p->size && p->data[p->pos] >= '0' && p->data[p->pos] <= '9') ++p->pos;
            if (p->pos == k) return -1;
        }
    }
    t->end = p->pos; t->next = p->count; return (int)n;
}
static int text(parser *p, unsigned n, const char *expected)
{
    token *t = &p->nodes[n]; size_t length = strlen(expected);
    return t->type == '"' && t->end-t->start == length+2 &&
        !memcmp(p->data+t->start+1, expected, length);
}
static int uuid7(const char *id)
{
    unsigned i;
    if (strlen(id) != 36 || id[14] != '7' || !strchr("89ab", id[19])) return 0;
    for (i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (id[i] != '-') return 0; }
        else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return 0;
    }
    return 1;
}
void tl_command_init(tl_command_cache *cache) { if (cache) memset(cache, 0, sizeof(*cache)); }
int tl_command_read(tl_command_cache *cache, const char *project, const char *device,
                    const char *topic, const char *json, size_t size, unsigned known,
                    unsigned door, const char *utc, tl_lock_id_provider next_id, void *context)
{
    parser p;
    char prefix[160], id[37], message_id[37], key[65];
    const char *error = NULL;
    unsigned i, target = 64, command = 64, input = 64, attempt = 64, empty;
    size_t length;
    int result;
    tl_command_reply reply;
    if (!cache || !project || !device || !topic || !json || size < 2 || size >= 512 ||
        known > 1 || door > 1 || !tl_mqtt_valid_utc(utc) || !next_id || cache->count > 8) return -1;
    result = snprintf(prefix, sizeof(prefix), "tc/v1/%s/%s/down/command/", project, device);
    if (result <= 0 || (size_t)result >= sizeof(prefix) || strncmp(topic, prefix, (size_t)result)) return -1;
    length = strlen(topic+(size_t)result);
    if (length != 36) return -1;
    memcpy(id, topic+(size_t)result, 37); if (!uuid7(id)) return -1;
    memset(&p, 0, sizeof(p)); p.data = json; p.size = size;
    if (value(&p, 0) != 0 || p.nodes[0].type != '{') return -1;
    space(&p); if (p.pos != size) return -1;
    for (i = 1; i < p.count; i = p.nodes[p.nodes[i].next].next) {
        unsigned n = p.nodes[i].next;
        if (text(&p, i, "targetDeviceKey")) target = n;
        else if (text(&p, i, "commandKey")) command = n;
        else if (text(&p, i, "input")) input = n;
        else if (text(&p, i, "attempt")) attempt = n;
        else return -1;
    }
    if (target == 64 || command == 64 || input == 64 || !text(&p, target, device) ||
        p.nodes[command].type != '"') return -1;
    if (attempt != 64) {
        uint32_t number = 0;
        token *t = &p.nodes[attempt];
        if (t->type != 'N') return -1;
        for (length = t->start; length < t->end; ++length) {
            unsigned digit = (unsigned char)json[length] - '0';
            if (digit > 9 || number > (2147483647u-digit)/10) return -1;
            number = number*10+digit;
        }
        if (!number) return -1;
    }
    length = p.nodes[command].end-p.nodes[command].start-2;
    if (!length || length > 64) return -1;
    memcpy(key, json+p.nodes[command].start+1, length); key[length] = 0;
    empty = p.nodes[input].type == '{' && !p.nodes[input].children;
    for (i = 0; i < cache->count; ++i) if (!strcmp(cache->replies[i].command_id, id)) {
        if (strcmp(cache->replies[i].command_key, key) || cache->replies[i].input_empty != empty) return -1;
        return (int)i+1;
    }
    if (cache->count == TL_COMMAND_CACHE_LIMIT) return -2;
    if (strcmp(key, "readDoorState")) error = "UNSUPPORTED_COMMAND";
    else if (!empty) error = "INVALID_INPUT";
    else if (!known) error = "STATE_UNAVAILABLE";
    memset(&reply, 0, sizeof(reply)); strcpy(reply.command_id, id); strcpy(reply.command_key, key);
    reply.input_empty = empty;
    if (next_id(context, message_id) || !uuid7(message_id)) return -1;
    if (error) result = snprintf(reply.json, sizeof(reply.json),
        "{\"messageId\":\"%s\",\"occurredAt\":\"%s\",\"status\":\"FAILED\",\"errorCode\":\"%s\",\"output\":{}}",
        message_id, utc, error);
    else result = snprintf(reply.json, sizeof(reply.json),
        "{\"messageId\":\"%s\",\"occurredAt\":\"%s\",\"status\":\"SUCCESS\",\"output\":{\"doorOpen\":%s}}",
        message_id, utc, door ? "true" : "false");
    if (result <= 0 || (size_t)result >= sizeof(reply.json)) return -1;
    reply.size = (size_t)result; cache->replies[cache->count++] = reply;
    return (int)cache->count;
}
int tl_command_reply_encode(const tl_command_reply *reply, unsigned char *buffer, size_t size,
                            const char *project, const char *device, uint16_t packet_id, int duplicate)
{
    if (!reply || reply->size >= sizeof(reply->json)) return -1;
    return tl_mqtt_command_reply(buffer, size, project, device, reply->command_id,
                                reply->json, reply->size, packet_id, duplicate);
}
