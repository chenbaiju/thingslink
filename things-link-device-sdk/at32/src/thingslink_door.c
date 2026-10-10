#include "thingslink_door.h"
#include <string.h>
int tl_door_init(tl_door_source *source, unsigned closed_level)
{
    if (!source || closed_level > 1) return -1;
    memset(source, 0, sizeof(*source)); source->closed_level = closed_level; return 0;
}
int tl_door_sample(tl_door_source *source, uint32_t now, unsigned level)
{
    unsigned door;
    if (!source || source->closed_level > 1) return -1;
    if (level > 1) { source->known = source->started = 0; return -1; }
    if (!source->started || (uint32_t)(now-source->last_tick) > 20) {
        source->known = 0; source->started = 1; source->candidate = level;
        source->candidate_since = source->last_tick = now; return 0;
    }
    source->last_tick = now;
    if (source->candidate != level) {
        source->candidate = level; source->candidate_since = now; return 0;
    }
    door = level != source->closed_level;
    if ((uint32_t)(now-source->candidate_since) >= 1000 &&
        (!source->known || source->door_open != door)) {
        source->known = 1; source->door_open = door; return 1;
    }
    return 0;
}
