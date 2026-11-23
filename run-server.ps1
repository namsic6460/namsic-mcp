#Requires -Version 5.1
<#
.SYNOPSIS
    namsic-mcp 서버를 릴리스 빌드한 뒤 바이너리로 직접 실행한다.

.DESCRIPTION
    cargo build --release 후 target\release\namsic-mcp.exe 를 실행한다.
    같은 폴더의 .env 는 바이너리가 직접 읽으므로(선택 사항) 스크립트가 따로 주입하지 않는다.
    adb·powershell 등은 호스트 PATH 의 로컬 바이너리를 그대로 사용한다.

.PARAMETER NoBuild
    빌드를 건너뛰고 기존 릴리스 바이너리를 실행한다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\run-server.ps1

.NOTES
    Rust 툴체인(rustup, stable)이 필요하다. 설정 키는 .env.example 참고.
#>
[CmdletBinding()]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not $NoBuild) {
    Write-Host '[빌드] cargo build --release' -ForegroundColor Green
    & cargo build --release
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$exe = Join-Path $PSScriptRoot 'target\release\namsic-mcp.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    Write-Error "실행 파일이 없습니다: $exe (먼저 -NoBuild 없이 실행하세요)"
}

Write-Host "[실행] $exe" -ForegroundColor Green
& $exe
exit $LASTEXITCODE
