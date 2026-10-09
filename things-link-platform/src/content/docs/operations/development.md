---
title: 源码开发与验证
description: 准备开发工具生成契约，按改动范围执行静态单元和整合验证
group: 运维开发
order: 420
---

# 源码开发与验证

参与开发时，先阅读仓库根 `AGENTS.md`、`docs/PROGRESS.md`、文档指南和测试方针，再读涉及工程的 README。进度记录说明当前候选，旧测试通过不自动证明新修改。

## 工具和目录

Java 后端要求 JDK21及 Maven；官网要求 Node22.12以上，Console/WebApp 按各自 engines；包管理使用锁定的 pnpm。Agent 要求 Python3.12以上并按其锁文件安装。真实依赖回归需要受管容器环境。

源码按工程和业务域放置：Java 在 `things-link/`，管理页在 `things-link-console/`，终端宿主在 `things-link-webapp/`，静态内容在 `things-link-platform/`。Client Contracts 提供无宿主依赖的客户端合同，Agent 和 SDK 有各自知识库。

## 开发基本顺序

1. 核对 Git 状态并保留既有修改，确认当前请求范围。
2. 阅读该功能契约、权限点与错误码，先写能体现风险的验证。
3. 实现接口或页面，保留身份切换、未知结果和迟响应保护。
4. 变更 HTTP 合同后，在仓库根运行统一生成入口。

```bash
python3 scripts/generate-openapi-contracts.py
```

不要手改 OpenAPI 或消费端生成类型。该操作需要按根 README 准备后端构建环境。

## 前端验证示例

在对应工程目录使用其冻结依赖：

```bash
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
```

上面用于 Console；官网则运行 `pnpm build`、`pnpm verify:docs`、`pnpm verify:accessibility`、`pnpm verify:deployment` 和 `pnpm verify:performance`。后端、Agent、SDK 与真实浏览器采用各自验证入口，不能用页面构建替代业务联验。

## 交付记录

把实际候选、命令、失败、通过与剩余限制写回已有功能文档，执行 `git diff --check` 和完整文档门禁。提交与推送按当次授权，远端 CI、服务器部署和真实硬件独立记录。
