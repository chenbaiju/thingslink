# ThingsLink 本地依赖栈管理
# 用法： cd deploy && make up
#
# macOS 自带 GNU Make 3.81，这里刻意只用 3.81 支持的语法。

SHELL := /bin/bash
COMPOSE := docker compose --env-file .env

.DEFAULT_GOAL := help

CORE := postgres redis redpanda emqx minio

.PHONY: help init up up-all up-tools up-obs bootstrap db-bootstrap down stop reset ps logs verify psql redis rpk urls

help: ## 显示可用命令
	@echo "ThingsLink 依赖栈"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'
	@echo ""

init: ## 从 .env.example 生成 .env（已存在则跳过）
	@if [ -f .env ]; then \
		echo "  .env 已存在，跳过"; \
	else \
		cp .env.example .env && echo "  已生成 deploy/.env"; \
	fi

up: init ## 启动核心栈（postgres / redis / redpanda / emqx / minio）
	$(COMPOSE) up -d --wait $(CORE)
	@$(MAKE) --no-print-directory bootstrap
	@echo ""
	@$(MAKE) --no-print-directory urls

# 初始化任务与核心栈分开跑：`up --wait` 会把「跑完就退出」的容器判定为失败，
# 所以 init 容器放在 init profile 里，这里用 run --rm 同步执行。全部幂等，可重复跑。
bootstrap: db-bootstrap ## 建库扩展 / Kafka 主题 / MinIO 桶（幂等，可重复执行）
	$(COMPOSE) run --rm redpanda-init
	$(COMPOSE) run --rm minio-init

db-bootstrap: ## 执行 postgres/init 下的 SQL（幂等）
	@for f in ./postgres/init/*.sql; do \
		echo "  applying $$f"; \
		$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 \
			-U $$(grep '^POSTGRES_USER=' .env | cut -d= -f2) \
			-d $$(grep '^POSTGRES_DB=' .env | cut -d= -f2) < $$f > /dev/null; \
	done
	@echo "  数据库初始化完成"

up-tools: init ## 追加启动管理 UI（Redpanda Console / pgAdmin）
	$(COMPOSE) --profile tools up -d --wait redpanda-console pgadmin

up-obs: init ## 追加启动可观测（Prometheus / Grafana）
	$(COMPOSE) --profile obs up -d --wait prometheus grafana

up-all: up up-tools up-obs ## 启动全部（核心 + 管理 UI + 可观测）
	@echo ""
	@$(MAKE) --no-print-directory urls

stop: ## 停止容器，保留数据
	$(COMPOSE) --profile "*" stop

down: ## 停止并删除容器，保留数据卷
	$(COMPOSE) --profile "*" down

reset: ## 【破坏性】删除容器与全部数据卷，从零重来
	@echo "这会删除所有本地数据：数据库、Kafka 消息、MinIO 对象、EMQX 状态。"
	@read -p "确认输入 yes： " ans; [ "$$ans" = "yes" ] || { echo "已取消"; exit 1; }
	$(COMPOSE) --profile "*" down -v
	@echo "已清空。执行 make up 重建。"

ps: ## 查看容器状态
	$(COMPOSE) --profile "*" ps

logs: ## 跟踪日志（make logs s=emqx 只看某个服务）
	$(COMPOSE) logs -f --tail=100 $(s)

verify: ## 逐项验证依赖栈是否真的可用（S0 验收用）
	@bash ./verify.sh

psql: ## 进入 psql
	$(COMPOSE) exec postgres psql -U $$(grep '^POSTGRES_USER=' .env | cut -d= -f2) -d $$(grep '^POSTGRES_DB=' .env | cut -d= -f2)

redis: ## 进入 redis-cli
	$(COMPOSE) exec redis redis-cli -a $$(grep '^REDIS_PASSWORD=' .env | cut -d= -f2)

rpk: ## 执行 rpk（make rpk c="topic list"）
	$(COMPOSE) exec redpanda rpk --brokers redpanda:9092 $(c)

urls: ## 打印各服务访问地址
	@echo "  PostgreSQL   localhost:$$(grep '^POSTGRES_PORT=' .env | cut -d= -f2)  库 thingslink / thingslink_test"
	@echo "  Redis        localhost:$$(grep '^REDIS_PORT=' .env | cut -d= -f2)"
	@echo "  Kafka        localhost:$$(grep '^REDPANDA_PORT=' .env | cut -d= -f2)   <- bootstrap.servers"
	@echo "  MQTT         localhost:$$(grep '^EMQX_MQTT_PORT=' .env | cut -d= -f2)"
	@echo "  EMQX 控制台  http://localhost:$$(grep '^EMQX_DASHBOARD_PORT=' .env | cut -d= -f2)"
	@echo "  MinIO API    http://localhost:$$(grep '^MINIO_PORT=' .env | cut -d= -f2)"
	@echo "  MinIO 控制台 http://localhost:$$(grep '^MINIO_CONSOLE_PORT=' .env | cut -d= -f2)"
	@echo ""
	@echo "  以下需 make up-tools / make up-obs："
	@echo "  Redpanda UI  http://localhost:$$(grep '^REDPANDA_CONSOLE_PORT=' .env | cut -d= -f2)"
	@echo "  pgAdmin      http://localhost:$$(grep '^PGADMIN_PORT=' .env | cut -d= -f2)"
	@echo "  Prometheus   http://localhost:$$(grep '^PROMETHEUS_PORT=' .env | cut -d= -f2)"
	@echo "  Grafana      http://localhost:$$(grep '^GRAFANA_PORT=' .env | cut -d= -f2)"
