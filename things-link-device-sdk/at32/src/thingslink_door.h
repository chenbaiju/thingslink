#ifndef THINGSLINK_DOOR_H
#define THINGSLINK_DOOR_H
#include <stdint.h>
typedef struct {
    uint32_t last_tick, candidate_since;
    unsigned closed_level, started, candidate, known, door_open;
} tl_door_source;
/* 两种原锁门磁极性相反；必须由已核验的固件配置明确选择关门电平。 */
int tl_door_init(tl_door_source *, unsigned);
/* 5ms轮询输入，间隔超过20ms取消可信状态；稳定1秒才接纳初始值或变化。 */
int tl_door_sample(tl_door_source *, uint32_t, unsigned);
#endif
