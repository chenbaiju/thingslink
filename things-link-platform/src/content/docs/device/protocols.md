---
title: HTTP TCP 与 CoAP 接入
description: 比较四种设备协议，并了解 HTTPS 上报 TCP 帧和 DTLS CoAP
group: 设备接入
order: 140
---

# HTTP TCP 与 CoAP 接入

选择设备硬件能正确实现的协议。设备直连入口与管理 API、公开集成 API 分开：控制台登录令牌与 API Key 不能代替设备凭据。

## 协议差异

| 协议 | 传输保护 | 命令交付 | 状态解释 |
| --- | --- | --- | --- |
| MQTT | TLS | 已连接设备订阅下行 | 连接在线 |
| HTTP | HTTPS | 设备主动领取 | 最近活动，不制造长连接在线 |
| TCP | TLS 长连接 | 平台在会话推送 | 连接与应用心跳 |
| CoAP | DTLS | 设备主动领取 | 最近活动，不制造长连接在线 |

四种协议提供属性与命令回复闭环，但网关、Modbus、属性设置和 OTA 等不能自动从 MQTT 推断到其他协议。接入配置只选择一种当前协议，切换前核对已有客户端与待执行动作。

## HTTPS 上报

设备向以下路径发送 JSON：

```text
POST /device-access/v1/property/report
X-TC-Device-Key: {projectKey}/{deviceKey}
X-TC-Device-Secret: 当前设备秘密
Content-Type: application/json; charset=utf-8
```

正文沿用[MQTT 属性格式](/docs/device/mqtt)。部署提供主机与证书，请求上限64 KiB。202 与 `ACCEPTED` 表示平台受理，之后仍检查数据事实；同消息 ID 异载荷会冲突。命令通过 `/device-access/v1/command/claim` 领取，并通过 `/device-access/v1/command/reply` 回复，领取租约不是完成结果。

## TCP 与 CoAP 客户端

TCP 不能把 JSON 直接写入 socket：每帧有10字节网络序头，包含 `TC` 魔数、版本、类型、零 flags 及载荷长度；载荷不超过65536字节。先认证，按返回间隔心跳，再上报并处理受理帧和命令帧。

CoAP 使用 DTLS 服务端证书验证；设备身份与秘密分别通过自定义选项65001、65002发送，资源路径与 HTTPS 设备入口一致。首批不提供 DTLS-PSK。空 ACK 只确认传输，等待独立业务响应。

## 接入前检查

请部署方提供对应协议端点和匹配版本的完整客户端合同；本页比较协议，不替代 TCP 编解码或 CoAP 选项实现。遇到429有界退避，凭据错误先修正配置；不要关闭 TLS 或扩大身份作用域。
