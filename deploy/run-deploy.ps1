<#
  run-deploy.ps1 — 一键执行 deploy 依赖栈（等价于 cd deploy && make up && make verify）
  只做这些事：生成 .env → 启动核心栈 → bootstrap(redpanda/minio) → 执行 postgres SQL → 跑 verify.sh
  不修改任何仓库源码，不删数据卷。
#>
$ErrorActionPreference = 'Continue'
$deploy = $PSScriptRoot
Set-Location $deploy

function Step($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "[OK]   $t" -ForegroundColor Green }
function Warn($t) { Write-Host "[WARN] $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "[FAIL] $t" -ForegroundColor Red }

Write-Host "工作目录: $deploy" -ForegroundColor Gray
Write-Host "时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray

# ---------- 0. 让 docker 可用 ----------
Step '0. Docker 可用性检查'
$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    if (Test-Path $dockerBin) { $env:Path = "$env:Path;$dockerBin"; Warn "已临时追加 PATH: $dockerBin" }
}
& docker version
if ($LASTEXITCODE -ne 0) {
    Fail 'docker 命令不可用（Docker Desktop 未运行或 CLI 不在 PATH），终止。'
    exit 1
}
Ok 'docker CLI 与引擎均可用'

# ---------- 1. init ----------
Step '1. init：生成 .env'
if (Test-Path .env) {
    Write-Host '  .env 已存在，跳过'
} else {
    Copy-Item .env.example .env
    Ok ' 已生成 deploy/.env（来自 .env.example）'
}

# ---------- 2. 启动核心栈 ----------
Step '2. up：启动核心栈 postgres / redis / redpanda / emqx / minio（首次会拉镜像，可能较久）'
& docker compose --env-file .env up -d --wait postgres redis redpanda emqx minio
$upRc = $LASTEXITCODE
Write-Host "  up 退出码 = $upRc"
if ($upRc -ne 0) { Warn 'up 未完全成功；继续执行后续步骤以便收集更多信息' } else { Ok '核心栈已启动并通过健康检查' }

# ---------- 3. bootstrap ----------
Step '3. bootstrap：Kafka 主题 / MinIO 桶'
& docker compose --env-file .env run --rm redpanda-init
Write-Host "  redpanda-init 退出码 = $LASTEXITCODE"
& docker compose --env-file .env run --rm minio-init
Write-Host "  minio-init 退出码 = $LASTEXITCODE"

# ---------- 4. db-bootstrap ----------
Step '4. db-bootstrap：执行 postgres/init 下的 SQL'
$pgUser = (((Select-String -Path .env -Pattern '^POSTGRES_USER=').Line) -split '=', 2)[1]
$pgDb   = (((Select-String -Path .env -Pattern '^POSTGRES_DB=').Line) -split '=', 2)[1]
Write-Host "  连接参数: -U $pgUser -d $pgDb"
$sqlFiles = Get-ChildItem .\postgres\init\*.sql -ErrorAction SilentlyContinue
if (-not $sqlFiles) {
    Warn '  未找到 postgres/init/*.sql'
} else {
    foreach ($f in $sqlFiles) {
        Write-Host "  applying $($f.Name)"
        cmd /c "docker compose --env-file .env exec -T postgres psql -v ON_ERROR_STOP=1 -U $pgUser -d $pgDb < `"$($f.FullName)`""
        Write-Host "    退出码 = $LASTEXITCODE"
    }
}

# ---------- 5. verify ----------
Step '5. verify：逐项验收（verify.sh）'
$env:MSYS_NO_PATHCONV = '1'
$bashCommand = Get-Command bash -ErrorAction SilentlyContinue
$bash = if ($bashCommand) { $bashCommand.Source } else { '' }
if ($bash -and (Test-Path -LiteralPath $bash)) {
    & $bash (Join-Path $deploy 'verify.sh')
    Write-Host "  verify.sh 退出码 = $LASTEXITCODE"
} else {
    Warn '  未找到 Git bash，改用 docker compose ps'
    & docker compose --env-file .env --profile '*' ps
}

# ---------- 6. 汇总 ----------
Step '6. 容器状态汇总'
& docker compose --env-file .env --profile '*' ps
Write-Host "`n完成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
