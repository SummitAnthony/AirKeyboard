@echo off
REM AirKeyboard server - Quick Start Batch File
REM This batch file builds and runs the server

echo ====================================
echo   AirKeyboard server
echo   Windows Edition
echo ====================================
echo.

if not exist "AirKeyboard.csproj" (
    echo Error: AirKeyboard.csproj not found!
    echo Please run this batch file from the project directory.
    pause
    exit /b 1
)

echo Checking .NET SDK...
dotnet --version
if errorlevel 1 (
    echo.
    echo Error: .NET SDK not found!
    echo Please install the .NET 8 SDK from: https://dotnet.microsoft.com/download
    pause
    exit /b 1
)

echo.
echo Building project...
dotnet build
if errorlevel 1 (
    echo.
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo   Build successful!
echo   Starting server...
echo ====================================
echo.

dotnet run -- --console

pause
