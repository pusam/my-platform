@echo off
chcp 65001 >nul
REM 개발 서버 배포 스크립트 (Windows -> Linux Remote Build)
REM 사용 전 deploy-config.bat 파일을 생성하세요 (deploy-config.bat.example 참고)

setlocal enabledelayedexpansion

echo 🚀 개발 서버 배포 시작 (서버 사이드 빌드 방식)...
echo ================================
echo.

REM 설정 파일 로드
if not exist deploy-config.bat (
    echo ❌ deploy-config.bat 파일이 없습니다!
    echo 📝 deploy-config.bat.example 파일을 복사하여 deploy-config.bat을 생성하세요.
    pause
    exit /b 1
)
call deploy-config.bat

REM 필수 변수 확인
if "%SERVER_USER%"=="" (
    echo ❌ SERVER_USER가 설정되지 않았습니다!
    pause
    exit /b 1
)
if "%SERVER_HOST%"=="" (
    echo ❌ SERVER_HOST가 설정되지 않았습니다!
    pause
    exit /b 1
)

REM 1. 자바 빌드 (윈도우에서 실행)
echo ☕ 자바 프로젝트 빌드 중 (Gradle)...
call gradlew clean bootJar
if errorlevel 1 (
    echo ❌ Gradle 빌드 실패!
    pause
    exit /b 1
)

REM 2. 파일 전송 (jar파일 + 설정파일만 보냄)
echo 📤 서버로 파일 전송 중...
REM (1) 빌드된 JAR 파일 찾기 (build/libs 폴더)
for %%f in (build\libs\*-SNAPSHOT.jar) do set JAR_FILE=%%f

REM (2) JAR 파일과 설정 파일 전송
scp -P %SERVER_PORT% -i "%SSH_KEY%" "%JAR_FILE%" "%REMOTE_DIR%/app.jar"
scp -P %SERVER_PORT% -i "%SSH_KEY%" docker-compose.yml Dockerfile .env setup-database.sql insert-default-data.sql "%SERVER_USER%@%SERVER_HOST%:%REMOTE_DIR%/"

REM (3) Nginx 설정 전송
scp -P %SERVER_PORT% -i "%SSH_KEY%" -r nginx "%SERVER_USER%@%SERVER_HOST%:%REMOTE_DIR%/"

if errorlevel 1 (
    echo ❌ 파일 전송 실패!
    pause
    exit /b 1
)

REM 3. 서버에서 도커 빌드 및 실행
echo 🐳 서버에서 도커 빌드 및 실행 중...
ssh -p %SERVER_PORT% -i "%SSH_KEY%" %SERVER_USER%@%SERVER_HOST% "cd %REMOTE_DIR% && rm -f .dockerignore && docker compose down && docker compose up -d --build && docker compose ps && docker compose logs --tail=20"

if errorlevel 1 (
    echo ❌ 서버 실행 실패!
    pause
    exit /b 1
)

echo.
echo ✅ 배포 완료!
echo ================================
echo 🌐 서버 URL: %SERVER_URL%
echo 📚 Swagger: %SERVER_URL%/swagger-ui/index.html
echo.
pause