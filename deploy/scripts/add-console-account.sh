#!/usr/bin/env bash
# ThingsLink 控制台账号管理脚本。
#
# 创建账号：
#   ./deploy/scripts/add-console-account.sh --email <邮箱> --password <密码> --display-name <显示名>
#   ./deploy/scripts/add-console-account.sh --email <邮箱> --password <密码> --display-name <显示名> --tenant-id <租户ID> --project-id <项目ID>
#
# 解锁账号：
#   ./deploy/scripts/add-console-account.sh --unlock --email <邮箱>
#
# 不指定 --tenant-id / --project-id 时会自动创建新的租户和项目。
# --password 长度 8-64 字符（与 PasswordPolicy 一致）。
#
# 依赖：deploy 栈必须已运行（cd deploy && make up）

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
EMAIL=""
PASSWORD=""
DISPLAY_NAME=""
TENANT_ID=""
PROJECT_ID=""
TENANT_NAME=""
PROJECT_NAME=""
UNLOCK_MODE=false

usage() {
    echo "用法:"
    echo "  $0 --email <邮箱> --password <密码> --display-name <显示名> [选项]"
    echo "  $0 --unlock --email <邮箱>"
    echo ""
    echo "创建账号必选:"
    echo "  --email <邮箱>         登录邮箱，全局唯一"
    echo "  --password <密码>      登录密码，8-64 字符"
    echo "  --display-name <名称>  显示名称，1-64 字符"
    echo ""
    echo "可选:"
    echo "  --tenant-id <UUID>     加入已有租户"
    echo "  --tenant-name <名称>   新建租户的名称（默认'默认租户'）"
    echo "  --project-id <UUID>    加入已有项目"
    echo "  --project-name <名称>  新建项目的名称（默认'默认项目'）"
    echo "  --plan-code <编码>     新建租户的档位（默认 FREE）：写入该档位的订阅与有效策略绑定，与注册路径同形"
    echo "  --legacy-runtime-baseline"
    echo "                         仅测试夹具：不写订阅、不绑策略，沿用 S7 运行时基线（宽松额度；生产补号不要用）"
    echo ""
    echo "解锁账号:"
    echo "  --unlock               解锁模式，配合 --email 使用"
    echo ""
    echo "示例:"
    echo "  $0 --email dev@example.com --password mypass123 --display-name 开发者"
    echo "  $0 --unlock --email admin@thingslink.local"
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --email)       EMAIL="$2"; shift 2 ;;
        --password)    PASSWORD="$2"; shift 2 ;;
        --display-name) DISPLAY_NAME="$2"; shift 2 ;;
        --tenant-id)   TENANT_ID="$2"; shift 2 ;;
        --tenant-name) TENANT_NAME="$2"; shift 2 ;;
        --project-id)  PROJECT_ID="$2"; shift 2 ;;
        --project-name) PROJECT_NAME="$2"; shift 2 ;;
        --plan-code)   PLAN_CODE="$2"; shift 2 ;;
        --legacy-runtime-baseline) LEGACY_RUNTIME_BASELINE=true; shift ;;
        --unlock)      UNLOCK_MODE=true; shift ;;
        -h|--help)     usage ;;
        *)             echo "未知参数: $1"; usage ;;
    esac
done

# 校验必选参数
if [[ -z "$EMAIL" ]]; then
    echo "错误: --email 为必选参数"
    usage
fi

# S14-6b：新租户的商用供给口径。默认按档位写入订阅与有效策略绑定（与注册路径同形）；
# 只有显式 --legacy-runtime-baseline 的测试夹具保留 S7 基线且不写订阅。二者互斥。
# 默认值必须在这里定下来：下面用 [[ ]] 展开该变量，未设值会让脚本在运行时报语法错误。
PLAN_CODE="${PLAN_CODE:-FREE}"
LEGACY_RUNTIME_BASELINE="${LEGACY_RUNTIME_BASELINE:-false}"
if [[ "$LEGACY_RUNTIME_BASELINE" == "true" ]] && [[ "$PLAN_CODE" != "FREE" ]]; then
    echo "错误: --legacy-runtime-baseline 与 --plan-code <非FREE> 互斥"
    usage
fi

# ---------------------------------------------------------------------------
# 加载 deploy/.env 获取数据库连接信息
# ---------------------------------------------------------------------------
if [[ -f "$DEPLOY_DIR/.env" ]]; then
    source <(grep -E '^POSTGRES_USER=|^POSTGRES_DB=' "$DEPLOY_DIR/.env")
fi
PGUSER="${POSTGRES_USER:-thingslink}"
# 隔离HTTP浏览器夹具可显式选择独立数据库；默认开发库保持原配置。
PGDB="${TC_CONSOLE_FIXTURE_DATABASE:-${POSTGRES_DB:-thingslink}}"
PGCONTAINER="${TC_CONSOLE_FIXTURE_POSTGRES_CONTAINER:-tc-postgres}"

# 封装 psql 调用，去除输出末尾换行
psql_query() {
    docker exec "$PGCONTAINER" psql -U "$PGUSER" -d "$PGDB" --no-psqlrc -t -A -c "$1" 2>/dev/null
}

psql_exec() {
    docker exec "$PGCONTAINER" psql -U "$PGUSER" -d "$PGDB" --no-psqlrc -v ON_ERROR_STOP=1 -q -c "$1" 2>&1
}

# 带 psql 变量的执行入口：供给 SQL 用 :'tenant_id' 这类占位符，由 psql 自行做字面量引用，
# 因此不做 sed 文本替换——名称里出现引号、斜杠或美元符号都不会破坏 SQL。
psql_exec_with_vars() {
    # 必须走 stdin（-f -）而不是 -c：psql 的 -c 不做事变量插值，:'tenant_id' 会原样发给服务端。
    # 由 psql 自己引用变量，因此名称里的引号/斜杠/美元符号都不会破坏 SQL。
    printf '%s\n' "$1" | docker exec -i "$PGCONTAINER" psql -U "$PGUSER" -d "$PGDB" --no-psqlrc \
        -v ON_ERROR_STOP=1 -q \
        -v tenant_id="$TENANT_ID" -v tenant_name="$TENANT_NAME" -v plan_code="$PLAN_CODE" \
        -f - 2>&1
}

# ---------------------------------------------------------------------------
# --unlock：解锁账号
# ---------------------------------------------------------------------------
if $UNLOCK_MODE; then
    EXISTING=$(psql_query "SELECT id FROM sys_account WHERE lower(email) = lower('$EMAIL') AND deleted_at IS NULL;")
    if [[ -z "$EXISTING" ]]; then
        echo "错误: 邮箱 $EMAIL 不存在"
        exit 1
    fi

    STATUS=$(psql_query "SELECT status FROM sys_account WHERE id = '$EXISTING';")
    ATTEMPTS=$(psql_query "SELECT failed_login_attempts FROM sys_account WHERE id = '$EXISTING';")
    LOCKED=$(psql_query "SELECT locked_until FROM sys_account WHERE id = '$EXISTING';")

    echo "账号 $EMAIL 当前状态:"
    echo "  status:                $STATUS"
    echo "  failed_login_attempts: ${ATTEMPTS:-0}"
    echo "  locked_until:          ${LOCKED:-无}"

    psql_exec "UPDATE sys_account SET failed_login_attempts = 0, locked_until = NULL, status = 'ACTIVE' WHERE id = '$EXISTING';"
    echo ""
    echo "账号 $EMAIL 已解锁，状态恢复为 ACTIVE。"
    exit 0
fi

# 校验创建模式必选参数
if [[ -z "$PASSWORD" || -z "$DISPLAY_NAME" ]]; then
    echo "错误: --password、--display-name 为必选参数"
    usage
fi

# 校验密码长度
if [[ ${#PASSWORD} -lt 8 || ${#PASSWORD} -gt 64 ]]; then
    echo "错误: 密码长度必须在 8-64 字符之间"
    exit 1
fi

# 校验显示名长度
if [[ ${#DISPLAY_NAME} -lt 1 || ${#DISPLAY_NAME} -gt 64 ]]; then
    echo "错误: 显示名长度必须在 1-64 字符之间"
    exit 1
fi

# 不能同时指定 tenant/project ID 和 name
if [[ -n "$TENANT_ID" && -n "$TENANT_NAME" ]]; then
    echo "错误: --tenant-id 和 --tenant-name 不能同时指定"
    exit 1
fi
if [[ -n "$PROJECT_ID" && -n "$PROJECT_NAME" ]]; then
    echo "错误: --project-id 和 --project-name 不能同时指定"
    exit 1
fi

# 默认值
TENANT_NAME="${TENANT_NAME:-默认租户}"
PROJECT_NAME="${PROJECT_NAME:-默认项目}"

# ---------------------------------------------------------------------------
# 业务逻辑
# ---------------------------------------------------------------------------

# 1. 检查邮箱是否已存在
echo "检查邮箱 $EMAIL ..."
EXISTING=$(psql_query "SELECT id FROM sys_account WHERE lower(email) = lower('$EMAIL') AND deleted_at IS NULL;")
if [[ -n "$EXISTING" ]]; then
    echo "错误: 邮箱 $EMAIL 已被使用"
    exit 1
fi
echo "  邮箱可用"

# 2. 生成 bcrypt 哈希（通过 pgcrypto，前缀 {bcrypt} 与 DelegatingPasswordEncoder 一致）
echo "生成密码哈希 ..."
PASSWORD_HASH=$(psql_query "SELECT '{bcrypt}' || crypt('$PASSWORD', gen_salt('bf', 10));")
if [[ -z "$PASSWORD_HASH" ]]; then
    echo "错误: 密码哈希生成失败"
    exit 1
fi
echo "  完成"

# 3. 生成 UUID
ACCOUNT_ID=$(psql_query "SELECT gen_random_uuid();")
TENANT_MEMBER_ID=$(psql_query "SELECT gen_random_uuid();")
PROJECT_MEMBER_ID=$(psql_query "SELECT gen_random_uuid();")

# 4. 处理租户
if [[ -n "$TENANT_ID" ]]; then
    EXISTS=$(psql_query "SELECT id FROM sys_tenant WHERE id = '$TENANT_ID' AND deleted_at IS NULL;")
    if [[ -z "$EXISTS" ]]; then
        echo "错误: 租户 $TENANT_ID 不存在"
        exit 1
    fi
    echo "加入已有租户: $TENANT_ID"
else
    TENANT_ID=$(psql_query "SELECT gen_random_uuid();")
    echo "创建新租户: $TENANT_NAME ($TENANT_ID)"
fi

# 5. 处理项目
PROJECT_KEY=""
if [[ -n "$PROJECT_ID" ]]; then
    EXISTS=$(psql_query "SELECT id FROM sys_project WHERE id = '$PROJECT_ID' AND deleted_at IS NULL;")
    if [[ -z "$EXISTS" ]]; then
        echo "错误: 项目 $PROJECT_ID 不存在"
        exit 1
    fi
    echo "加入已有项目: $PROJECT_ID"
else
    PROJECT_ID=$(psql_query "SELECT gen_random_uuid();")
    EMAIL_PREFIX="${EMAIL%%@*}"
    PROJECT_KEY="${EMAIL_PREFIX}-$(echo "$PROJECT_ID" | tr -d '-' | head -c 8)"
    echo "创建新项目: $PROJECT_NAME ($PROJECT_ID) key=$PROJECT_KEY"
fi

# 6. 事务写入
echo "写入数据库 ..."
# S14-6b：租户供给 SQL 放在 deploy/sql/，由本脚本与真库用例共用同一份文本（单一事实来源）；
# 这里把文件内容拼进同一事务，保证「租户行 + 订阅 + 策略绑定 + 账号 + 项目」要么全成、要么全滚。
if [[ "$LEGACY_RUNTIME_BASELINE" == "true" ]]; then
    TENANT_PROVISIONING_SQL="$(cat "${DEPLOY_DIR}/sql/legacy-console-tenant.sql")"
else
    if [[ -z "$(psql_query "SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id = r.plan_id WHERE p.code = '$PLAN_CODE' AND r.revision_code = 'product-revision-1';")" ]]; then
        echo "错误: 档位 $PLAN_CODE 在 product-revision-1 中不存在（先确认迁移与目录播种已执行）"
        exit 1
    fi
    TENANT_PROVISIONING_SQL="$(cat "${DEPLOY_DIR}/sql/provision-console-tenant.sql")"
fi
psql_exec_with_vars "
BEGIN;

-- 租户（仅在新建时）：供给 SQL 来自 deploy/sql/（见那里的不变量说明）
$TENANT_PROVISIONING_SQL

-- 账号
INSERT INTO sys_account (id, email, password_hash, display_name, status, email_verified_at)
VALUES ('$ACCOUNT_ID', '$EMAIL', '$PASSWORD_HASH', '$DISPLAY_NAME', 'ACTIVE', now());

-- 租户-账号关联
INSERT INTO sys_tenant_member (id, tenant_id, account_id, status)
VALUES ('$TENANT_MEMBER_ID', '$TENANT_ID', '$ACCOUNT_ID', 'ACTIVE');

-- 项目（仅在新建时）
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_project WHERE id = '$PROJECT_ID') THEN
        INSERT INTO sys_project (id, tenant_id, name, region, status, project_key)
        VALUES ('$PROJECT_ID', '$TENANT_ID', '$PROJECT_NAME', 'sh-1', 'ACTIVE', '$PROJECT_KEY');
    END IF;
END
\$\$;

-- 项目-账号关联（OWNER）
INSERT INTO sys_project_member (id, project_id, account_id, role, status)
VALUES ('$PROJECT_MEMBER_ID', '$PROJECT_ID', '$ACCOUNT_ID', 'OWNER', 'ACTIVE');

COMMIT;
"

echo ""
echo "============================================"
echo "  账号创建成功"
echo "============================================"
echo "  邮箱:     $EMAIL"
echo "  密码:     $PASSWORD"
echo "  显示名:   $DISPLAY_NAME"
echo "  角色:     OWNER"
echo "  租户:     $TENANT_NAME ($TENANT_ID)"
echo "  项目:     $PROJECT_NAME ($PROJECT_ID)"
if [[ -z "${PROJECT_KEY:-}" ]]; then
    echo "  项目Key:  加入已有项目，由项目创建时指定"
else
    echo "  项目Key:  $PROJECT_KEY"
fi
echo ""
echo "  登录后即可进入控制台。"
echo "============================================"
