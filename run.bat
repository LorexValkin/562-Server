@echo off
REM ============================================================
REM  562 Dev Server - Launcher
REM  Chase Foster - Educational Game Design Project
REM ============================================================

if not exist bin\com\rs562\server\Server.class (
    echo [ERROR] Server not built. Run build.bat first.
    pause
    exit /b 1
)

echo [LAUNCH] Starting 562 Dev Server...
echo.

java -Xmx256m -Xms128m -cp bin com.rs562.server.Server

pause
