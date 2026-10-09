---
title: 平台文档
description: 从设备接入到业务应用的学习路径、使用指南和开发资料
group: 平台基础
order: 0
---

# 平台文档

ThingsLink 将项目、设备、数据、告警、规则和应用组织在同一平台中。本指南帮助项目管理员准备资源、设备开发者接入设备、业务开发者建立应用，运维人员核对运行结果。

## 选择学习路径

| 你要完成的工作 | 建议阅读顺序 | 完成后检查 |
| --- | --- | --- |
| 接入第一台设备 | [快速开始](/docs/getting-started) → [物模型](/docs/device/thing-model) → [MQTT 接入](/docs/device/mqtt) | 当前值与原始历史能对应一次上报 |
| 搭建监测应用 | [温湿度监测实战](/docs/tutorials/environment-monitoring) → [告警通知](/docs/business/alarms) → [看板](/docs/business/dashboards) | 同一设备的数据、告警、组件绑定一致 |
| 实现远程控制 | [命令与期望状态](/docs/device/commands) → [控制回执实战](/docs/tutorials/remote-control) | 设备回复对应原命令，重复消息不重复执行 |
| 对接已有系统 | [API 与 Webhook](/docs/business/integrations) → [只读集成实战](/docs/tutorials/server-integration) | 权限、游标、限流和未知结果恢复正确 |
| 理解和维护平台 | [技术栈](/docs/overview/technology) → [架构与部署](/docs/operations/deployment) → [开发与验证](/docs/operations/development) | 环境、候选、备份和验证结果可追溯 |

## 文档分区

- **平台基础**：[平台介绍](/docs/overview/what-is-thingslink)、[核心概念](/docs/overview/concepts)、[技术栈](/docs/overview/technology)。
- **快速上手**：[准备第一台设备](/docs/getting-started)、[项目与协作](/docs/getting-started/projects)。
- **设备接入**：物模型、MQTT、其他协议、网关、设备管理、遥测与命令。
- **业务开发**：告警、消息规则、自动化、任务、看板、应用、终端用户、集成、额度、OTA 和 Agent。
- **运维开发**：部署、源码开发与故障排查。
- **实战教程**：通过模拟输入完成监测、控制和服务端集成。

## 使用约定

先取得所属环境的控制台地址、项目权限与接入配置。教程中的名称、数值和设备为教学示例，地址和秘密需使用自己的配置；示例阈值不用于生产安全判断。

当前版本面向公司内部服务器和来源 IP 白名单使用，平台保留订阅、额度和用量管理。公网服务、商用云部署和 SIM 服务暂不开放；展示指南不意味着已有公开服务。了解功能区别，请从[平台介绍](/docs/overview/what-is-thingslink)开始。
