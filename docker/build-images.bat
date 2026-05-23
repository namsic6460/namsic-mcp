@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
if "%~1"=="" (
    set "CONFIG_FILE=%SCRIPT_DIR%\images.conf"
) else (
    set "CONFIG_FILE=%~1"
)

if not exist "%CONFIG_FILE%" (
    echo Config file not found: %CONFIG_FILE%
    echo.
    echo Usage: %~nx0 [config-file-path]
    echo Default: %SCRIPT_DIR%\images.conf
    exit /b 1
)

echo === Docker Image Build Start ===
echo Config: %CONFIG_FILE%
echo.

rem --- Phase 1: Build base image(s) synchronously ---
echo --- Phase 1: Base image(s) ---
set "base_built=0"

for /f "usebackq eol=# tokens=1,2,3,4,5" %%A in ("%CONFIG_FILE%") do (
    set "image_tag=%%A"
    set "node_version=%%B"
    set "dockerfile=%%E"

    if not "!node_version!"=="" (
        if "!dockerfile!"=="base.Dockerfile" (
            echo Building base: !image_tag!
            docker build -t "!image_tag!" -f "%SCRIPT_DIR%\!dockerfile!" "%SCRIPT_DIR%"
            if !errorlevel! equ 0 (
                echo OK: !image_tag!
                set /a base_built+=1
            ) else (
                echo FAILED: !image_tag!
                echo Base image build failed. Aborting.
                exit /b 1
            )
        )
    )
)

echo Base image(s) built: %base_built%
echo.

rem --- Phase 2: Build remaining images in parallel ---
echo --- Phase 2: Remaining images (parallel) ---

set "count=0"
set "LOG_DIR=%TEMP%\docker-build-%RANDOM%"
mkdir "%LOG_DIR%" 2>nul

for /f "usebackq eol=# tokens=1,2,3,4,5" %%A in ("%CONFIG_FILE%") do (
    set "image_tag=%%A"
    set "node_version=%%B"
    set "java_version=%%C"
    set "jdk_dist=%%D"
    set "dockerfile=%%E"

    if not "!node_version!"=="" (
        if "!dockerfile!"=="" set "dockerfile=Dockerfile"

        rem Skip base images (already built in Phase 1)
        if not "!dockerfile!"=="base.Dockerfile" (
            set /a count+=1
            set "tag_!count!=!image_tag!"
            set "TASK=%LOG_DIR%\!count!.cmd"

            if "!java_version!"=="-" set "java_version="
            if "!jdk_dist!"=="-" set "jdk_dist="
            if "!jdk_dist!"=="" set "jdk_dist=temurin"

            > "!TASK!" echo @echo off
            if "!java_version!"=="" (
                echo Starting: !image_tag! ^(Node !node_version!, !dockerfile!^)
                >> "!TASK!" echo docker build --build-arg "NODE_VERSION=!node_version!" -t "!image_tag!" -f "%SCRIPT_DIR%\!dockerfile!" "%SCRIPT_DIR%" ^> "%LOG_DIR%\!count!.log" 2^>^&1
            ) else (
                echo Starting: !image_tag! ^(Node !node_version!, Java !java_version!, JDK !jdk_dist!, !dockerfile!^)
                >> "!TASK!" echo docker build --build-arg "NODE_VERSION=!node_version!" --build-arg "JAVA_VERSION=!java_version!" --build-arg "JDK_DIST=!jdk_dist!" -t "!image_tag!" -f "%SCRIPT_DIR%\!dockerfile!" "%SCRIPT_DIR%" ^> "%LOG_DIR%\!count!.log" 2^>^&1
            )
            >> "!TASK!" echo if %%errorlevel%% equ 0 ^(echo OK ^> "%LOG_DIR%\!count!.status"^) else ^(echo FAILED ^> "%LOG_DIR%\!count!.status"^)
            start /b cmd /c "!TASK!"
        )
    )
)

echo.
echo Waiting for %count% build(s)...

:wait_loop
set "done=0"
for /l %%i in (1,1,%count%) do (
    if exist "%LOG_DIR%\%%i.status" set /a done+=1
)
if %done% lss %count% (
    timeout /t 2 /nobreak >nul
    goto wait_loop
)

echo.
set "built=0"
set "failed=0"

for /l %%i in (1,1,%count%) do (
    findstr /c:"OK" "%LOG_DIR%\%%i.status" >nul 2>&1
    if !errorlevel! equ 0 (
        echo OK: !tag_%%i!
        set /a built+=1
    ) else (
        echo FAILED: !tag_%%i!
        set /a failed+=1
    )
)

rd /s /q "%LOG_DIR%" 2>nul

set /a "total=base_built+built"
echo.
echo === Build Done: %total% succeeded, %failed% failed ===
if %failed% gtr 0 exit /b 1
