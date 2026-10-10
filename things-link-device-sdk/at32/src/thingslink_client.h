#ifndef THINGSLINK_AT32_CLIENT_H
#define THINGSLINK_AT32_CLIENT_H
#include <stdint.h>
#include <stddef.h>
#include "thingslink_lock_uplink.h"

#define TL_TEST_CONFIG_READY 0x544C4331u
/* 台架专用RAM置备，不写Flash；每次复位重新置备可信时间和随机种子。 */
typedef struct {
    volatile uint32_t ready;
    uint32_t version;
    uint32_t utc;
    unsigned char ip[4];
    uint16_t port;
    uint16_t dn_size;
    char hostname[65];
    char project[65];
    char device[65];
    char client_id[65];
    char token[65];
    unsigned char entropy[32];
    unsigned char ca_dn[256];
    unsigned char ca_point[65];
} tl_test_config;

typedef struct {
    /* 非阻塞：返回已复制字节数、0等待、-1断开。 */
    int (*send)(const unsigned char *, size_t);
    int (*receive)(unsigned char *, size_t);
} tl_transport;

/* 状态只含阶段/错误/计数，不含凭据和报文内容。 */
typedef struct {
    volatile uint32_t phase;
    volatile uint32_t tls_error;
    volatile uint32_t reports_acked;
    volatile uint32_t reconnects;
    volatile uint32_t mqtt_error;
} tl_status;
extern volatile tl_status tl_diagnostics;
int tl_client_init(tl_test_config *, uint32_t);
int tl_client_connected(uint32_t);
int tl_client_poll(const tl_transport *, uint32_t);
void tl_client_disconnected(void);
/* 接受一个转换结果：1已复制、0单在途忙、-1无效；PUBACK仅为Broker确认。 */
int tl_client_submit_lock(const tl_lock_message *);
/* 使用当前已置备时间和随机源；适用于转换器回调，不写Flash。 */
int tl_client_next_id(void *, char [37]);
/* 显式启用C01；状态来自已核验消抖采样，未初始化只能FAILED。不调用UART。 */
int tl_client_enable_door_diagnostic(void);
int tl_client_set_door_state(unsigned, unsigned);
#endif
