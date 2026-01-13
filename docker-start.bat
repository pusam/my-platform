@echo off
REM My Platform - Docker Quick Start Script (Windows)

setlocal enabledelayedexpansion

echo 🐳 My Platform Docker Setup
echo ================================
echo.

REM Check if .env exists
if not exist .env (
    echo ⚠️  .env file not found!
    echo 📝 Creating .env from .env.example...
    copy .env.example .env
    echo ✅ Please edit .env file and set your passwords!
    echo.
    echo Required settings:
    echo   - MYSQL_ROOT_PASSWORD
    echo   - MYSQL_PASSWORD
    echo   - REDIS_PASSWORD
    echo   - JWT_SECRET
    echo.
    pause
)

REM Check Docker
docker --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Docker is not installed!
    pause
    exit /b 1
)

docker-compose --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Docker Compose is not installed!
    pause
    exit /b 1
)

echo ✅ Docker and Docker Compose are installed
echo.

REM Menu
echo Select an option:
echo 1) Start all services (build + run)
echo 2) Start all services (run only)
echo 3) Stop all services
echo 4) View logs
echo 5) Restart backend
echo 6) Clean up (remove containers and volumes)
echo 7) Exit
echo.
set /p option="Enter option (1-7): "

if "%option%"=="1" (
    echo 🔨 Building and starting all services...
    docker-compose up -d --build
    echo.
    echo ✅ Services started!
    echo 🌐 Application: http://localhost
    echo 📚 Swagger: http://localhost/swagger-ui/index.html
    echo.
    echo 📊 View logs: docker-compose logs -f
    pause
) else if "%option%"=="2" (
    echo 🚀 Starting all services...
    docker-compose up -d
    echo.
    echo ✅ Services started!
    pause
) else if "%option%"=="3" (
    echo 🛑 Stopping all services...
    docker-compose down
    echo ✅ Services stopped!
    pause
) else if "%option%"=="4" (
    echo 📊 Viewing logs (Ctrl+C to exit)...
    docker-compose logs -f
) else if "%option%"=="5" (
    echo 🔄 Restarting backend...
    docker-compose restart backend
    echo ✅ Backend restarted!
    pause
) else if "%option%"=="6" (
    set /p confirm="⚠️  This will remove all containers and data! Continue? (y/N): "
    if /i "!confirm!"=="y" (
        echo 🗑️  Cleaning up...
        docker-compose down -v
        echo ✅ Cleanup complete!
    ) else (
        echo Cancelled.
    )
    pause
) else if "%option%"=="7" (
    echo 👋 Goodbye!
    exit /b 0
) else (
    echo ❌ Invalid option!
    pause
    exit /b 1
)

