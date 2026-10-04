[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'ArchiveAndDestroy')]
    [string]$Action,

    [string]$EvidenceDirectory,

    [string]$BackendLog,

    [string]$ArchivePath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ComposeProject = 'c4a1c'
$ExpectedComposeContainers = @(
    'c4a1c-postgres',
    'c4a1c-redis',
    'c4a1c-redpanda',
    'c4a1c-redpanda-init',
    'c4a1c-emqx',
    'c4a1c-minio',
    'c4a1c-minio-init'
)
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$DeployDirectory = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'deploy'))
$BaseCompose = [System.IO.Path]::GetFullPath((Join-Path $DeployDirectory 'docker-compose.yml'))
$OverlayCompose = [System.IO.Path]::GetFullPath((Join-Path $DeployDirectory 'c4a1c-compose.yml'))
$EnvironmentFile = [System.IO.Path]::GetFullPath((Join-Path $DeployDirectory '.env'))
$BootstrapJar = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot `
    'things-link\things-link-bootstrap\target\things-link-bootstrap-0.0.1-SNAPSHOT.jar'))

function Assert-FileExists {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Label)
    if (-not [System.IO.File]::Exists($Path)) {
        throw "$Label 不存在: $Path"
    }
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$OutputPath
    )
    $output = & $Executable @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "外部命令失败($LASTEXITCODE): $Executable $($Arguments -join ' ')`n$($output -join "`n")"
    }
    if ($OutputPath) {
        [System.IO.File]::WriteAllLines($OutputPath, [string[]](Protect-DiagnosticLines -Lines $output),
            [System.Text.UTF8Encoding]::new($false))
    }
    return $output
}

function Protect-DiagnosticLines {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Lines)
    return @($Lines | ForEach-Object {
        $line = [string]$_
        $line = $line -replace '(?i)((?:password|secret|token|cookie|access.?key|access.?token)[^:=\s"'']*["'']?\s*[:=]\s*["'']?)[^,}\s"'']+', '${1}<redacted>'
        $line -replace '(?i)(authorization\s*:\s*bearer\s+)[^\s"'']+', '${1}<redacted>'
    })
}

function Protect-StagedTextFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object {
        $_.Extension -in @('.json', '.jsonl', '.log', '.txt', '.yml', '.yaml', '.conf')
    } | ForEach-Object {
        $lines = [System.IO.File]::ReadAllLines($_.FullName)
        $protectedLines = @(Protect-DiagnosticLines -Lines $lines)
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        if ($protectedLines.Count -eq 0) {
            # 空 manifest 是“尚未刷新”的有效诊断事实；保留空文件，而不是让归档门闩异常退出。
            [System.IO.File]::WriteAllText($_.FullName, '', $utf8NoBom)
        } else {
            [System.IO.File]::WriteAllLines($_.FullName, [string[]]$protectedLines, $utf8NoBom)
        }
    }
}

function Get-ComposeArguments {
    param([Parameter(Mandatory = $true)][string[]]$Tail)
    return @('compose', '--env-file', $EnvironmentFile, '-f', $BaseCompose, '-f', $OverlayCompose,
        '-p', $ComposeProject) + $Tail
}

function Assert-StaticInputs {
    Assert-FileExists -Path $BaseCompose -Label '基础 Compose'
    Assert-FileExists -Path $OverlayCompose -Label 'C4a-1c overlay'
    Assert-FileExists -Path $EnvironmentFile -Label 'Compose 环境文件'
    Assert-FileExists -Path $BootstrapJar -Label '当前 Bootstrap JAR'
    $null = Get-Command docker -ErrorAction Stop
    $null = Invoke-Native -Executable 'docker' -Arguments (Get-ComposeArguments -Tail @('config', '--quiet'))
}

function Get-ProjectContainers {
    $lines = Invoke-Native -Executable 'docker' -Arguments @(
        'ps', '-a', '--filter', "label=com.docker.compose.project=$ComposeProject", '--format', '{{.Names}}'
    )
    return @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-ProjectResources {
    param([Parameter(Mandatory = $true)][ValidateSet('network', 'volume')][string]$Kind)
    $lines = Invoke-Native -Executable 'docker' -Arguments @(
        $Kind, 'ls', '--filter', "label=com.docker.compose.project=$ComposeProject", '--format', '{{.Name}}'
    )
    return @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Assert-OnlyExpectedContainers {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Names)
    $unexpected = @($Names | Where-Object { $_ -notin $ExpectedComposeContainers })
    if ($unexpected.Count -gt 0) {
        throw "Compose 项目包含白名单外容器，拒绝继续: $($unexpected -join ', ')"
    }
}

function Invoke-Preflight {
    Assert-StaticInputs
    $existing = @(Get-ProjectContainers)
    Assert-OnlyExpectedContainers -Names $existing
    if ($existing.Count -gt 0) {
        throw "发现既有 c4a1c Compose 资源，必须先人工确认其证据状态: $($existing -join ', ')"
    }
    $existingVolumes = @(Get-ProjectResources -Kind 'volume')
    $existingNetworks = @(Get-ProjectResources -Kind 'network')
    if ($existingVolumes.Count -gt 0 -or $existingNetworks.Count -gt 0) {
        throw "发现既有 c4a1c 卷或网络，拒绝复用旧状态: volumes=$($existingVolumes -join ',') " +
            "networks=$($existingNetworks -join ',')"
    }
    Write-Host "[c4a1c-runner] PREFLIGHT PASS repo=$RepositoryRoot jar=$BootstrapJar"
}

function Resolve-RequiredAbsolutePath {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Label)
    if (-not [System.IO.Path]::IsPathFullyQualified($Path)) {
        throw "$Label 必须是绝对路径: $Path"
    }
    return [System.IO.Path]::GetFullPath($Path)
}

function Invoke-ArchiveAndDestroy {
    Assert-StaticInputs
    if (-not $EvidenceDirectory -or -not $ArchivePath) {
        throw 'ArchiveAndDestroy 必须提供 EvidenceDirectory 与 ArchivePath'
    }
    $absoluteEvidence = Resolve-RequiredAbsolutePath -Path $EvidenceDirectory -Label '证据目录'
    $absoluteArchive = Resolve-RequiredAbsolutePath -Path $ArchivePath -Label '归档文件'
    if (-not [System.IO.Directory]::Exists($absoluteEvidence)) {
        throw "证据目录不存在: $absoluteEvidence"
    }
    if ([System.IO.Path]::GetExtension($absoluteArchive) -ne '.zip') {
        throw "归档文件必须使用 .zip 后缀: $absoluteArchive"
    }
    $archiveParent = [System.IO.Path]::GetDirectoryName($absoluteArchive)
    [System.IO.Directory]::CreateDirectory($archiveParent) | Out-Null
    if ([System.IO.File]::Exists($absoluteArchive)) {
        throw "归档文件已存在，拒绝覆盖: $absoluteArchive"
    }

    $containers = @(Get-ProjectContainers)
    Assert-OnlyExpectedContainers -Names $containers
    if ($containers.Count -eq 0) {
        throw '没有可归档的 c4a1c Compose 容器，拒绝执行销毁'
    }

    $staging = [System.IO.Path]::GetFullPath((Join-Path $archiveParent `
        ("c4a1c-archive-{0}" -f [System.Guid]::NewGuid().ToString('N'))))
    [System.IO.Directory]::CreateDirectory($staging) | Out-Null
    $rawTarget = Join-Path $staging 'raw'
    Copy-Item -LiteralPath $absoluteEvidence -Destination $rawTarget -Recurse
    if ($BackendLog) {
        $absoluteBackendLog = Resolve-RequiredAbsolutePath -Path $BackendLog -Label 'Bootstrap 日志'
        Assert-FileExists -Path $absoluteBackendLog -Label 'Bootstrap 日志'
        $backendLines = [System.IO.File]::ReadAllLines($absoluteBackendLog)
        [System.IO.File]::WriteAllLines((Join-Path $staging 'backend.log'),
            [string[]](Protect-DiagnosticLines -Lines $backendLines), [System.Text.UTF8Encoding]::new($false))
    }

    $diagnostics = Join-Path $staging 'diagnostics'
    [System.IO.Directory]::CreateDirectory($diagnostics) | Out-Null
    $null = Invoke-Native -Executable 'docker' -Arguments (Get-ComposeArguments -Tail @('config')) `
        -OutputPath (Join-Path $diagnostics 'resolved-compose.yml')
    foreach ($container in $containers) {
        $null = Invoke-Native -Executable 'docker' -Arguments @('inspect', $container) `
            -OutputPath (Join-Path $diagnostics "$container.inspect.json")
        $null = Invoke-Native -Executable 'docker' -Arguments @('logs', '--timestamps', $container) `
            -OutputPath (Join-Path $diagnostics "$container.log")
    }
    $inventory = [ordered]@{
        schemaVersion = 1
        composeProject = $ComposeProject
        repositoryRoot = $RepositoryRoot
        evidenceDirectory = $absoluteEvidence
        bootstrapJar = $BootstrapJar
        containers = $containers
        collectedAt = [DateTimeOffset]::UtcNow.ToString('O')
    }
    [System.IO.File]::WriteAllText((Join-Path $diagnostics 'inventory.json'),
        ($inventory | ConvertTo-Json -Depth 4), [System.Text.UTF8Encoding]::new($false))
    Protect-StagedTextFiles -Root $staging

    Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $absoluteArchive -CompressionLevel Optimal
    $archiveInfo = [System.IO.FileInfo]::new($absoluteArchive)
    if (-not $archiveInfo.Exists -or $archiveInfo.Length -le 0) {
        throw "归档为空或不存在，保留隔离栈: $absoluteArchive"
    }
    $hash = (Get-FileHash -LiteralPath $absoluteArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -notmatch '^[0-9a-f]{64}$') {
        throw "归档摘要无效，保留隔离栈: $absoluteArchive"
    }
    [System.IO.File]::WriteAllText("$absoluteArchive.sha256", "$hash  $($archiveInfo.Name)`n",
        [System.Text.UTF8Encoding]::new($false))

    # down -v 位于归档存在性、非空与摘要核验之后；上方任一异常都会保留隔离栈。
    $null = Invoke-Native -Executable 'docker' -Arguments (Get-ComposeArguments -Tail @('down', '-v', '--remove-orphans'))
    # Compose 5.4 在 abort-on-container-exit 产生的已退出 init 容器上可能残留；只能按已核验白名单精确补删。
    $remainingContainers = @(Get-ProjectContainers)
    Assert-OnlyExpectedContainers -Names $remainingContainers
    foreach ($container in $remainingContainers) {
        $project = Invoke-Native -Executable 'docker' -Arguments @(
            'inspect', '-f', '{{index .Config.Labels "com.docker.compose.project"}}', $container
        )
        if (($project -join '').Trim() -ne $ComposeProject) {
            throw "容器项目标签漂移，拒绝补删: $container"
        }
        $null = Invoke-Native -Executable 'docker' -Arguments @('rm', '-f', $container)
    }
    $containerPostcondition = @(Get-ProjectContainers)
    $volumePostcondition = @(Get-ProjectResources -Kind 'volume')
    $networkPostcondition = @(Get-ProjectResources -Kind 'network')
    if ($containerPostcondition.Count -gt 0 -or $volumePostcondition.Count -gt 0 `
            -or $networkPostcondition.Count -gt 0) {
        throw "销毁后置条件失败 containers=$($containerPostcondition -join ',') " +
            "volumes=$($volumePostcondition -join ',') networks=$($networkPostcondition -join ',')"
    }
    $cleanupReceipt = [ordered]@{
        schemaVersion = 1
        composeProject = $ComposeProject
        archiveSha256 = $hash
        destroyedAt = [DateTimeOffset]::UtcNow.ToString('O')
        containers = @($containerPostcondition)
        volumes = @($volumePostcondition)
        networks = @($networkPostcondition)
        passed = $true
    }
    $cleanupPath = "$absoluteArchive.cleanup.json"
    [System.IO.File]::WriteAllText($cleanupPath, ($cleanupReceipt | ConvertTo-Json -Depth 4),
        [System.Text.UTF8Encoding]::new($false))
    $cleanupHash = (Get-FileHash -LiteralPath $cleanupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$cleanupPath.sha256", "$cleanupHash  $([System.IO.Path]::GetFileName($cleanupPath))`n",
        [System.Text.UTF8Encoding]::new($false))
    Write-Host "[c4a1c-runner] ARCHIVE PASS sha256=$hash archive=$absoluteArchive"
    Write-Host "[c4a1c-runner] DESTROY PASS project=c4a1c cleanupSha256=$cleanupHash"
}

switch ($Action) {
    'Preflight' { Invoke-Preflight }
    'ArchiveAndDestroy' { Invoke-ArchiveAndDestroy }
}
