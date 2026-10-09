---
title: API Key 与 Webhook
description: 安全签发项目 API Key，调用只读接口并处理签名 Webhook
group: 业务开发
order: 340
---

# API Key 与 Webhook

API Key 用于业务服务器读取或控制明确项目的资源；Webhook 将允许的事件投递给外部 HTTPS 接收端。两种集成需部署启用及当前管理资格，不因页面存在自动开放。

## API Key

在“项目与资源 → API Key”签发名称、最小 scope、来源 IP/CIDR 与到期时间。scope 包括设备读取、告警读取和设备控制，实际访问还取决于签发账号当前权限。CIDR 使用调用服务真实出口，不能填域名。

完整秘密仅首次显示，保存在服务器秘密管理中，不放浏览器、设备固件、日志或 Git。轮换立即撤销旧 Key，没有双 Key 宽限；原 Key 的命令结果不转给新 Key。

公开资源前缀为 `/api/open/v1`，请求使用 `X-Api-Key`，不混入 Console 登录令牌或 cookie。例如 `GET /api/open/v1/devices` 读取目录。成功返回直接 DTO，失败检查 code、message 和 traceId；分页复用返回游标，429 按 Retry-After 退避。

## 未知操作结果

签发、轮换或撤销失败可能已经被服务器处理。先“查询原操作”，查询只能恢复元数据，不能找回秘密。确认已创建而秘密丢失时明确轮换或撤销，不连续签发猜测结果。

命令请求带原 Idempotency-Key，202 只表示受理；用原命令 ID 或业务键查询完成情况。身份改变后不能复用别人的恢复记录。

## Webhook 接收

创建订阅时选择允许事件和设备范围，使用符合校验的 HTTPS 目标。此公开集成禁止内网或特殊用途地址；内部服务器范围不取消出站 SSRF 防护。

接收端按原始 UTF-8 字节验证 HMAC-SHA256：timestamp 秒、nonce、deliveryId、精确 body 用换行连接，校验时间窗口和 nonce，并按 deliveryId 持久去重。不要重排 JSON 后验签。2xx 表示远端 HTTP 确认，不代表对方业务最终完成。

查看投递、拒绝和原操作记录，恢复只能用于符合资格的原交付，不能随意重放。先从[只读服务端集成](/docs/tutorials/server-integration)建立最小范围。
