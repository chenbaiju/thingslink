#ifndef THINGSLINK_LOCK_UPLINK_H
#define THINGSLINK_LOCK_UPLINK_H
#include "thingslink_mqtt.h"

#define TL_LOCK_SOURCE_LIMIT 8
#define TL_LOCK_BATCH_LIMIT 3
#define TL_LOCK_JSON_LIMIT 512
#define TL_LOCK_PASSWORD_VALID 1u
#define TL_LOCK_CARD_VALID 2u
#define TL_LOCK_INVALID (-1)
#define TL_LOCK_FULL (-2)
#define TL_LOCK_TIME_UNKNOWN (-3)
#define TL_LOCK_CONFLICT (-4)

/* ID由已置备随机源生成；必须为独立UUIDv7，不用时间/计数拼接。 */
typedef int (*tl_lock_id_provider)(void *, char [37]);
typedef struct {
    unsigned kind;
    size_t size;
    char json[TL_LOCK_JSON_LIMIT];
} tl_lock_message;
/* 调用方保存整个批次直到确认；重发不得再次转换或重新分配ID。 */
typedef struct {
    unsigned count;
    tl_lock_message messages[TL_LOCK_BATCH_LIMIT];
} tl_lock_batch;
typedef struct {
    char source_id[37];
    char message_ids[4][37];
    unsigned char raw[16];
    uint32_t utc;
    unsigned used, sensor_seen, record_seen, door, valid_fields, id_count;
} tl_lock_source;
typedef struct {
    char model_version[33];
    tl_lock_id_provider next_id;
    void *id_context;
    unsigned door_known, door;
    uint32_t door_utc;
    tl_lock_source sources[TL_LOCK_SOURCE_LIMIT];
} tl_lock_uplink;

int tl_lock_uplink_init(tl_lock_uplink *, const char *, tl_lock_id_provider, void *);
/* 入口为已消抖的逻辑门状态；未知时钟返回TIME_UNKNOWN，不替换发生时间。 */
int tl_lock_uplink_sensor(tl_lock_uplink *, const char *, uint32_t, unsigned, tl_lock_batch *);
/* 原记录必须为16字节、CRC8有效；可选字段有效性由已核验的来源显式提供。 */
int tl_lock_uplink_record(tl_lock_uplink *, const char *, const unsigned char *, size_t,
                          unsigned, tl_lock_batch *);
/* 返回消息数、0同源已处理、负数失败；满缓存拒绝，禁止静默丢弃来源身份。 */
int tl_lock_message_encode(const tl_lock_message *, unsigned char *, size_t,
                            const char *, const char *, uint16_t, int);
#endif
