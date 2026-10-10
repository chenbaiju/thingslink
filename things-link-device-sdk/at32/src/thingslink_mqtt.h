#ifndef THINGSLINK_AT32_MQTT_H
#define THINGSLINK_AT32_MQTT_H

#include <stddef.h>
#include <stdint.h>

enum {
    TL_MQTT_PROPERTY = 0, TL_MQTT_DOOR_CHANGED, TL_MQTT_UNLOCK_RECORD,
    TL_MQTT_ABNORMAL_OPEN, TL_MQTT_PASSWORD_TRIAL
};
/* 正文由业务转换器构建；只允许本片五种上行路由，QoS1且非retained。 */
int tl_mqtt_publish(unsigned char *, size_t, const char *, const char *,
                     unsigned, const char *, size_t, uint16_t, int);

/* 原板最小测试客户端：单个QoS1在途上报，不实现锁控命令。 */
int tl_mqtt_connect(unsigned char *, size_t, const char *, const char *,
                    const char *, const char *);
int tl_mqtt_subscribe(unsigned char *, size_t, const char *, const char *, uint16_t);
int tl_mqtt_online(unsigned char *, size_t, const char *, const char *,
                   const char *, const char *, uint16_t, int);
/* 返回1表示完整帧、0表示等待更多字节、-1表示非法或超过容量。 */
int tl_mqtt_frame(const unsigned char *, size_t, size_t, size_t *);
/* 只读诊断命令的标准回复路由，命令ID必须来自已校验下行。 */
int tl_mqtt_command_reply(unsigned char *, size_t, const char *, const char *,
                          const char *, const char *, size_t, uint16_t, int);
int tl_mqtt_valid_utc(const char *);

#endif
