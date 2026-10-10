/* JG-M01原板候选：网络上行及只读PB3门磁诊断；物理来源须另行验收。 */
#include "UserSys.h"
#include "CH395INC.H"
#include "CH395CMD.H"
#include "CH395_APP.h"
#include "thingslink_client.h"
#include "thingslink_door.h"
#include <string.h>

tl_test_config tl_ram_config;
volatile uint32_t tl_millis;
volatile uint32_t tl_board_phase;
volatile uint32_t tl_board_error;
volatile unsigned char tl_board_ip[4];
/* 台架诊断：当前调用、调用起点、最长轮询和启动看门狗标志，不记录报文或秘密。 */
volatile uint32_t tl_board_trace[4];
/* 链接器单独保留末尾64字节SRAM，跨热复位保存故障位置；不写Flash。 */
volatile uint32_t tl_retained_trace[16] __attribute__((section("TL_RESET_TRACE")));
volatile uint32_t tl_previous_boot[16];
static unsigned chip_version, has_ip, tcp_connected, sending;
static uint32_t reconnect_at, send_started;
static tl_door_source door_source;

static void trace(unsigned step)
{
    tl_board_trace[0] = step; tl_board_trace[1] = tl_millis;
    tl_retained_trace[2] = step; tl_retained_trace[3] = tl_millis;
}

void tl_hard_fault(uint32_t *frame)
{
    uint32_t address = (uint32_t)frame;
    tl_retained_trace[8] = address >= 0x20000000 && address <= 0x20007FE0 ? frame[6] : 0;
    tl_retained_trace[9] = SCB->CFSR;
    tl_retained_trace[10] = SCB->HFSR;
    trace(255);
    for (;;) { /* 保留原看门狗复位机制。 */ }
}

__asm void HardFault_Handler(void)
{
    IMPORT tl_hard_fault
    TST LR, #4
    ITE EQ
    MRSEQ R0, MSP
    MRSNE R0, PSP
    B tl_hard_fault
}

/* 替代旧门磁/锁控定时路径，仅保留驱动所用倒计时。 */
void TMR2_GLOBAL_IRQHandler(void)
{
    unsigned i;
    if (TMR_GetINTStatus(TMR2, TMR_INT_Overflow) != RESET) {
        TMR_ClearITPendingBit(TMR2, TMR_INT_Overflow); ++tl_millis;
        tl_retained_trace[4] = tl_board_phase;
        tl_retained_trace[5] = tl_diagnostics.phase;
        tl_retained_trace[6] = tl_millis;
        for (i = 0; i < TM_MAX_CNT; ++i)
            if (SysVar.Timing[i] > 1) --SysVar.Timing[i];
    }
}
static void disconnected(void)
{
    if (tcp_connected) tl_client_disconnected();
    tcp_connected = sending = 0; reconnect_at = tl_millis + 3000;
    trace(10);
    CH395CloseSocket(0); tl_board_phase = 3;
}
static void events(void)
{
    unsigned interrupts, socket_interrupt;
    unsigned char ip[20], unreachable[10];
    interrupts = chip_version >= 0x44 ? CH395CMDGetGlobIntStatus_ALL() : CH395CMDGetGlobIntStatus();
    if (interrupts & GINT_STAT_PHY_CHANGE) {
        if (CH395CMDGetPHYStatus() == PHY_DISCONN) { has_ip = 0; disconnected(); }
        else CH395DHCPEnable(1);
    }
    if (interrupts & GINT_STAT_DHCP) {
        if (!CH395GetDHCPStatus()) {
            CH395GetIPInf(ip); memcpy((void *)tl_board_ip, ip, 4);
            has_ip = 1; tl_board_phase = 3;
        }
    }
    if (interrupts & GINT_STAT_UNREACH) CH395CMDGetUnreachIPPT(unreachable);
    if (interrupts & GINT_STAT_IP_CONFLI) { has_ip = 0; disconnected(); }
    if (interrupts & GINT_STAT_SOCK0) {
        socket_interrupt = CH395GetSocketInt(0);
        if (socket_interrupt & (SINT_STAT_TIM_OUT | SINT_STAT_DISCONNECT)) { disconnected(); return; }
        if (socket_interrupt & SINT_STAT_SEND_OK) sending = 0;
        if (socket_interrupt & SINT_STAT_CONNECT) {
            tcp_connected = 1; sending = 0; tl_board_phase = 4;
            trace(14);
            if (tl_client_connected(tl_millis)) { tl_board_error = 4; disconnected(); }
        }
    }
}
static int send_record(const unsigned char *data, size_t size)
{
    if (!tcp_connected) return -1;
    if (sending) return (uint32_t)(tl_millis-send_started) > 10000 ? -1 : 0;
    if (size > 512) size = 512;
    /* CH395命令将数据复制到芯片发送缓存；完成中断前不再次提交。 */
    trace(13);
    CH395SendData(0, (unsigned char *)data, (UINT16)size);
    sending = 1; send_started = tl_millis; return (int)size;
}
static int receive_record(unsigned char *data, size_t size)
{
    unsigned count;
    if (!tcp_connected) return -1;
    trace(11);
    count = CH395GetRecvLength(0);
    if (count > size) count = size;
    if (count > 256) count = 256;
    if (count) {
        trace(12);
        CH395GetRecvData(0, (UINT16)count, data);
    }
    return (int)count;
}
int main(void)
{
    static const tl_transport transport = {send_record, receive_record};
    uint32_t last_poll = 0, started, duration;
    unsigned result, i;
    NVIC_SetVectorTable(0x08002000, 0);
    tl_board_trace[3] = RCC_GetFlagStatus(RCC_FLAG_IWDGRST) != RESET;
    for (i = 0; i < 16; ++i) tl_previous_boot[i] = tl_retained_trace[i];
    result = tl_retained_trace[0] == 0x4D303152 ? tl_retained_trace[1] + 1 : 1;
    for (i = 0; i < 16; ++i) tl_retained_trace[i] = 0;
    tl_retained_trace[0] = 0x4D303152; tl_retained_trace[1] = result;
    tl_retained_trace[7] = tl_board_trace[3];
    RCC_ClearFlag();
    Hardware_Init(); User_GPIO_Init(); tl_board_phase = 1;
    while (tl_ram_config.ready != TL_TEST_CONFIG_READY) SYS_Ddt_Deed();
    if (tl_client_init(&tl_ram_config, tl_millis)) {
        tl_board_error = 1; for (;;) SYS_Ddt_Deed();
    }
    /* 不调用KEY_IO_DOOR_DET：该旧函数还会登记复位按键及锁控忙状态。 */
#if defined(ENABLE_LXS_FIRMWARE) && !defined(ENABLE_FDS_FIRMWARE)
    tl_door_init(&door_source, 0);
#elif defined(ENABLE_FDS_FIRMWARE) && !defined(ENABLE_LXS_FIRMWARE)
    tl_door_init(&door_source, 1);
#else
#error "Door polarity requires exactly one verified firmware variant"
#endif
    tl_client_enable_door_diagnostic();
    CH395Reset();
    result = CH395CMDCheckExist(0x65);
    if (result != 0x9A) { tl_board_error = 0x100u | result; for (;;) SYS_Ddt_Deed(); }
    chip_version = CH395CMDGetVer();
    result = CH395CMDInitCH395();
    if (result) { tl_board_error = 0x200u | result; for (;;) SYS_Ddt_Deed(); }
    CH395DHCPEnable(1); tl_board_phase = 2; reconnect_at = tl_millis;
    for (;;) {
        SYS_Ddt_Deed();
        if ((uint32_t)(tl_millis-last_poll) < 5) continue;
        last_poll = tl_millis;
        tl_door_sample(&door_source, last_poll, IO_DOOR_DET_READ());
        tl_client_set_door_state(door_source.known, door_source.door_open);
        trace(6);
        events();
        if (has_ip && !tcp_connected && (int32_t)(tl_millis-reconnect_at) >= 0) {
            trace(10);
            CH395CloseSocket(0);
            CH395SetSocketDesIP(0, tl_ram_config.ip);
            CH395SetSocketDesPort(0, tl_ram_config.port);
            CH395SetSocketSourPort(0, 50000);
            CH395SetSocketProtType(0, PROTO_TYPE_TCP);
            CH395SetSocketRecvBuf(0, 0, 16); CH395SetSocketSendBuf(0, 16, 16);
            trace(7);
            result = CH395OpenSocket(0);
            trace(8);
            if (!result) result = CH395TCPConnect(0);
            tl_board_error = result;
            reconnect_at = tl_millis + 15000;
        }
        if (tcp_connected) {
            started = tl_millis;
            trace(9);
            result = tl_client_poll(&transport, started);
            duration = tl_millis - started;
            if (duration > tl_board_trace[2]) tl_board_trace[2] = duration;
            if (result) disconnected();
        }
    }
}
