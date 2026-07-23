#Requires -Version 5.1
<#
.SYNOPSIS
    IDE 없이 namsic-mcp 서버를 로컬(호스트)에서 실행한다.

.DESCRIPTION
    같은 폴더의 .env 를 읽어 환경변수로 주입한 뒤 ./gradlew bootRun 으로 서버를 띄운다.
    도커를 쓰지 않으므로 docker / adb / node(Playwright) 등은 전부 호스트(Windows)에
    설치된 로컬 바이너리를 PATH 에서 그대로 사용한다. 네트워크·파일시스템도 로컬 그대로다.

.PARAMETER EnvFile
    읽어들일 .env 경로. 기본값은 스크립트와 같은 폴더의 .env.

.PARAMETER Task
    실행할 Gradle 태스크. 기본값 bootRun. (예: bootJar 로 빌드만 하고 싶을 때 -Task bootJar)

.PARAMETER GradleArgs
    gradlew 에 그대로 전달할 추가 인자.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\run-server.ps1

.NOTES
    실행 정책 때문에 막히면 위 예시처럼 -ExecutionPolicy Bypass 로 실행하거나,
    한 번 `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` 을 적용하면 된다.
    JDK 25 가 필요하다(build.gradle toolchain). 없으면 Gradle 이 받거나 빌드가 실패한다.
#>
[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [string]$Task = 'bootRun',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# --- .env 로드 ---------------------------------------------------------------
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Error ".env 파일이 없습니다: $EnvFile`n.env.example 을 .env 로 복사한 뒤 VAULT_USERNAME / VAULT_PASSWORD 를 채우세요."
}

$loaded = New-Object System.Collections.Generic.List[string]
foreach ($raw in Get-Content -LiteralPath $EnvFile) {
    $line = $raw.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    if ($line.StartsWith('export ')) { $line = $line.Substring(7).Trim() }

    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { continue }

    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1).Trim()

    # 값 양끝을 감싼 따옴표 제거
    if ($val.Length -ge 2 -and (
            ($val.StartsWith('"') -and $val.EndsWith('"')) -or
            ($val.StartsWith("'") -and $val.EndsWith("'")))) {
        $val = $val.Substring(1, $val.Length - 2)
    }

    Set-Item -Path "Env:$key" -Value $val
    $loaded.Add($key)
}

# --- 필수 값 확인 ------------------------------------------------------------
$required = @('VAULT_USERNAME', 'VAULT_PASSWORD')
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
if ($missing) {
    Write-Error ("다음 필수 환경변수가 .env 에 비어 있습니다: {0}" -f ($missing -join ', '))
}

Write-Host ("[.env] 로드 완료: {0}" -f ($loaded -join ', ')) -ForegroundColor Cyan
Write-Host ("[실행] gradlew {0} --no-daemon {1}" -f $Task, ($GradleArgs -join ' ')) -ForegroundColor Green

# --- 서버 실행 ---------------------------------------------------------------
# --no-daemon: 재사용되는 Gradle 데몬은 자신이 처음 뜰 때의 환경변수를 붙잡아 두므로,
#              방금 주입한 .env 값이 bootRun 이 fork 하는 JVM 까지 확실히 전달되도록 데몬을 끈다.
$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
& $gradlew $Task '--no-daemon' @GradleArgs
exit $LASTEXITCODE
