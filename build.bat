@echo off
REM ============================================================
REM  562 Dev Server - Build Script
REM  Chase Foster - Educational Game Design Project
REM ============================================================

setlocal enabledelayedexpansion

where javac >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "JAVAC=javac"
) else if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
) else (
    echo [ERROR] javac not found. Install JDK 8+ or set JAVA_HOME.
    pause
    exit /b 1
)

echo [BUILD] Using: !JAVAC!
echo [BUILD] Compiling 562 server...

if exist bin rd /s /q bin
mkdir bin

dir /s /b src\*.java > sources.txt
"!JAVAC!" -d bin @sources.txt 2> build_errors.txt
del sources.txt

if %ERRORLEVEL% neq 0 (
    echo [BUILD] FAILED. See build_errors.txt:
    type build_errors.txt
    pause
    exit /b 1
)

echo [BUILD] Success!
del build_errors.txt 2>nul
pause
