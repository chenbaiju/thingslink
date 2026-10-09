---
title: 技术栈与工程组成
description: 了解 Java 后端、设备接入、数据存储、前端、Agent 与验证工具
group: 平台基础
order: 30
---

# 技术栈与工程组成

平台按业务域划分 Maven 模块，管理面与设备接入面可以分别装配运行。工程边界帮助隔离长连接负载、业务事务、页面状态和模型调用。

## 后端与数据链路

| 层次 | 当前工程采用 | 职责 |
| --- | --- | --- |
| 后端 | Java 21、Spring Boot 4.1、Spring MVC、Spring Security | HTTP、认证、领域用例和事务 |
| 持久化 | Spring JDBC、显式 SQL、Flyway | 查询、行锁、版本比较和迁移 |
| 主库与时序 | PostgreSQL、TimescaleDB | 业务事实、JSONB、租户 RLS、时序历史 |
| 热数据 | Redis | 在线态、热缓存和速率控制，不是唯一事实来源 |
| 消息总线 | Kafka | 持久交接、异步消费和故障重放 |
| MQTT | EMQX | 设备连接、认证、ACL 和上下行 |
| 其他协议 | JDK TLS 与虚拟线程、Californium 与 Scandium | TCP 帧处理和 CoAP DTLS |
| 脚本 | GraalJS | 受限制的规则及编解码运行 |
| 对象 | S3 兼容存储 | 固件和导出对象等文件 |
| 观测 | Actuator、Micrometer | 健康、指标和运行诊断 |

技术组件的存在不意味着任意部署已经具备集群、高可用或容量资格，实际拓扑由部署方维护。

## 前端与辅助服务

| 工程 | 技术与用途 |
| --- | --- |
| Console | Vue 3、TypeScript、Vite、Element Plus、Pinia；管理和运维 SPA |
| WebApp | Vue 3、TypeScript、Vite；独立终端应用宿主 |
| Platform | Astro 7、Tailwind CSS 4、Markdown 内容集合；静态官网与文档 |
| Client Contracts | 版本化 TypeScript 合同包；共享看板 Schema 与校验规则 |
| Agent | Python 3.12 以上、Pydantic、FastAPI；受控分析合同与校验 |
| Device SDK | ESP32 独立工程；软件验证与真实板卡验收分别进行 |

## 为什么这样组合

PostgreSQL 保持业务事实和权限边界，TimescaleDB 承载历史查询，Redis 降低热路径成本，Kafka 将接入与业务处理解耦。看板和应用共享版本化合同，官网保持静态输出，无需把管理应用运行时加载到每篇教程。

## 验证工具

Java 使用 JUnit、Testcontainers、Awaitility 等；前端使用 Vitest 和 Playwright。OpenAPI 与消费端类型通过统一生成入口维护。精确依赖版本以各工程清单和锁文件为准，升级不能仅凭本页表格。

进一步阅读[架构与部署](/docs/operations/deployment)和[开发与验证](/docs/operations/development)。
