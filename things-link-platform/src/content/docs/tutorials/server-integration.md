---
title: 实战 服务端只读数据集成
description: 以最小 scope 和固定出口读取设备目录，正确处理游标限流与失败
group: 实战教程
order: 530
---

# 实战 服务端只读数据集成

本教程让一个业务服务器读取项目设备目录，先建立只读链路，再考虑控制或 Webhook。需要 API 集成已启用、项目管理资格、可信 HTTPS 地址与调用服务器固定出口。

## 1 签发最小权限

在 API Key 页面选择 device:read，配置当前服务器出口 IP/CIDR 和适当到期时间。立即把秘密写入服务器受保护配置，关闭一次性展示窗口。不要在浏览器发请求，也不把Key写进脚本正文。

## 2 请求设备目录

下面示例变量由受保护环境注入，地址必须是该部署的实际 HTTPS 地址；CA 文件由部署方提供。

```bash
curl --silent --show-error --fail-with-body \
  --cacert "$TC_CA_FILE" \
  -H "X-Api-Key: $TC_API_KEY" \
  "$TC_API_ORIGIN/api/open/v1/devices?limit=20"
```

使用受控运维会话，关闭命令追踪和含秘密的请求日志；命令行参数仍可能被同机进程观察，长期运行服务应从秘密管理器读取。不要添加 Console Authorization 或 cookie。

## 3 处理完整分页

解析 items、hasMore 和 nextCursor。hasMore 为真时将 nextCursor 作为下一次请求的 cursor 参数，使用 HTTP 客户端正确编码参数，并保持 Key、筛选和 limit 不变；false 后结束。游标是不可拆解的值，不自行构造。用设备 ID 去重，拒绝重复游标，限制异常循环。

不要从本页条数猜总数。更改筛选后从首请求开始，账号或项目失权后清理旧显示。查询当前值与历史前先取得匹配模型版本，缺值不填零。

## 4 错误和速率

401/403 类拒绝先查 Key、出口、账号权限与项目状态；429 按 Retry-After 退避；服务异常保留 traceId 并有界重试。协议层成功仍需校验响应结构，不把不可读数据记为空目录。

## 验收与扩展

将目录中的设备身份与控制台同项目列表对照，并验证该Key不能调用管理API或控制命令。完成后按需要接入[签名 Webhook](/docs/business/integrations)，控制能力另按[命令回执教程](/docs/tutorials/remote-control)验证，不能给只读服务默认全部 scope。
