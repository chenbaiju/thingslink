#ifndef THINGSLINK_COMMAND_H
#define THINGSLINK_COMMAND_H
#include "thingslink_lock_uplink.h"
#define TL_COMMAND_CACHE_LIMIT 8
#define TL_COMMAND_REPLY_LIMIT 256
typedef struct {
    char command_id[37], command_key[65];
    char json[TL_COMMAND_REPLY_LIMIT];
    size_t size;
    unsigned input_empty;
} tl_command_reply;
typedef struct {
    tl_command_reply replies[TL_COMMAND_CACHE_LIMIT];
    unsigned count;
} tl_command_cache;
/* 仅同一启动周期保留原终态；满缓存拒绝，不驱逐旧ID。禁止用于有副作用命令。 */
void tl_command_init(tl_command_cache *);
/* 返回缓存下标+1，-1拒绝/冲突，-2缓存满；known只接收消抖后的可信门状态。 */
int tl_command_read(tl_command_cache *, const char *, const char *, const char *,
                    const char *, size_t, unsigned, unsigned, const char *,
                    tl_lock_id_provider, void *);
int tl_command_reply_encode(const tl_command_reply *, unsigned char *, size_t,
                            const char *, const char *, uint16_t, int);
#endif
