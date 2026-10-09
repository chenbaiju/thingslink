---
title: MQTT 设备接入
description: 使用一机一密和 TLS 连接，理解 Topic 属性上报及消息重试
group: 设备接入
order: 130
---

# MQTT 设备接入

MQTT 适用于保持连接的设备和网关。本教程采用一机一密：每台直连设备使用自己的身份与设备密钥，网关另行代报子设备。

## 连接参数

| 参数 | 取值 |
| --- | --- |
| Broker | 由当前部署提供的主机、TLS 端口和 CA |
| MQTT 版本 | MQTT 3.1.1 兼容 |
| 用户名 | `{projectKey}/{deviceKey}` |
| 密码 | 当前有效设备密钥 |
| Client ID | 稳定且可诊断的设备连接标识 |
| QoS | 上下行均为1 |
| retained | false |

从设备接入配置核对协议与启用状态。TLS 客户端必须校验主机名和 CA。凭据轮换或撤销会影响活跃连接，应使用新凭据重连。

## 订阅与上报

设备订阅自身下行：

```text
tc/v1/{projectKey}/{deviceKey}/down/#
```

属性上报 Topic：

```text
tc/v1/{projectKey}/{deviceKey}/up/property/report
```

示例要求绑定模型中包含两个数值属性。把版本、时间和消息 ID 换成实际值后发送：

```json
{
  "messageId": "01993c89-7b21-7a31-8f90-aabbccddeeff",
  "modelVersion": "1.0.0",
  "occurredAt": "2026-10-08T08:30:00Z",
  "payload": {"temperature": 23.5, "humidity": 55}
}
```

`messageId` 为 UUIDv7。`occurredAt` 是采集时间，不能把模板时间用于每次采集；过度领先服务端时间会被拒绝。`payload` 非空，标识、类型和范围须匹配模型。每次新采集产生新 ID；同一次消息重试复用原 ID 和原正文。

## 判断成功

MQTT PUBACK 表示传输层确认。到控制台“消息日志”和设备“属性”“原始属性历史”核对结果，检查值、采集时间及版本。迟到值可能进入历史而不覆盖较新当前值。

认证失败先查项目与设备 Key、秘密状态和协议配置。ACL 拒绝先查 Topic 是否属于自身，不换其他设备的 Topic 尝试绕过。命令格式见[命令与期望状态](/docs/device/commands)，网关见[网关与 Modbus](/docs/device/gateway)。
