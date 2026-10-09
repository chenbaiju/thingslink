---
title: 命令与期望状态
description: 下发命令与属性设置，并分清受理投递设备确认和完成
group: 设备接入
order: 180
---

# 命令与期望状态

命令调用设备已经定义的功能，期望状态表达希望设备应用的属性。操作者需要当前设备控制权限，设备客户端需支持对应协议和模型。

## MQTT 命令流程

设备订阅自身下行后，从下面的 Topic 接收命令：

```text
tc/v1/{projectKey}/{deviceKey}/down/command/{commandId}
```

正文包含 `targetDeviceKey`、`commandKey`、`input` 与 `attempt`。直连设备只处理目标标识等于自身的命令。以 `commandId` 持久去重，不能以 attempt 作为新的业务键。

回复发往：

```text
tc/v1/{projectKey}/{deviceKey}/up/command/{commandId}/reply
```

```json
{
  "messageId": "01993c89-8a10-7b20-91aa-aabbccddeeff",
  "occurredAt": "2026-10-08T08:30:01Z",
  "status": "SUCCESS",
  "output": {"sequence": 1}
}
```

新回复使用真实时间及有效 UUIDv7。状态允许 ACK、SUCCESS、FAILED；ACK 不代表完成。输出匹配命令模型。重复收到同命令时返回已保存的原结果，不重复执行硬件副作用。

## 属性设置

MQTT 属性下发使用 `/down/property/set`，载荷含 `requestId` 与 `properties`。设备按 requestId 去重并在 `/up/property/set/reply` 回复。Desired 与 Reported 分开保存；回复和新的上报才能说明实际结果。其他协议不能直接假设支持此动作。

## 查看结果

控制台命令列表或执行记录提供受理、尝试、回复与失败事实。HTTP 或 CoAP 设备主动领取，平台受理时投递尝试可能仍为零。离线、超时和拒绝需按实际终态处理。

网络失败后先查询原操作或原命令，不换业务键立即重发；平台幂等与设备执行去重两层都需要实现。教学流程见[控制回执实战](/docs/tutorials/remote-control)。
