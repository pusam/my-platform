# My Platform - Docker Deployment Guide

## 🐳 Docker 구성

### 서비스 구성
- **MariaDB**: 데이터베이스 (포트 3306)
- **Redis**: 세션 및 캐시 (포트 6379)
- **Backend**: Spring Boot 애플리케이션 (포트 8080)
- **Nginx**: 리버스 프록시 (포트 80)

## 🚀 빠른 시작

### 1. 환경 변수 설정
```bash
cp .env.example .env
# .env 파일을 열어서 실제 비밀번호로 변경
```

### 2. Docker 이미지 빌드 및 실행
```bash
# 전체 빌드 및 실행
docker-compose up -d --build

# 로그 확인
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f backend
```

### 3. 접속
- 애플리케이션: http://localhost
- Swagger API: http://localhost/swagger-ui/index.html
- 직접 백엔드: http://localhost:8080

## 📋 주요 명령어

### 시작/중지
```bash
# 시작
docker-compose up -d

# 중지
docker-compose down

# 중지 및 볼륨 삭제
docker-compose down -v
```

### 재시작
```bash
# 전체 재시작
docker-compose restart

# 특정 서비스만 재시작
docker-compose restart backend
```

### 로그 확인
```bash
# 전체 로그
docker-compose logs -f

# 마지막 100줄
docker-compose logs --tail=100 backend

# 실시간 로그
docker-compose logs -f backend
```

### 상태 확인
```bash
# 서비스 상태
docker-compose ps

# 헬스체크 상태
docker ps --format "table {{.Names}}\t{{.Status}}"
```

## 🔧 개발 환경

### 로컬 개발 시 Docker 사용
```bash
# DB와 Redis만 실행
docker-compose up -d mariadb redis

# 애플리케이션은 로컬에서 실행
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

## 📦 프로덕션 배포

### 1. 이미지 빌드
```bash
docker-compose build --no-cache
```

### 2. 이미지 태그
```bash
docker tag myplatform-backend:latest your-registry/myplatform-backend:v1.0.0
```

### 3. 이미지 푸시
```bash
docker push your-registry/myplatform-backend:v1.0.0
```

### 4. 프로덕션 서버에서 실행
```bash
docker-compose -f docker-compose.yml up -d
```

## 🔐 보안 설정

### 환경 변수 (.env)
**반드시 변경해야 할 항목:**
```bash
MYSQL_ROOT_PASSWORD=강력한_루트_비밀번호
MYSQL_PASSWORD=강력한_MySQL_비밀번호
REDIS_PASSWORD=강력한_Redis_비밀번호
JWT_SECRET=최소_256비트_이상의_랜덤_문자열
```

### JWT Secret 생성 예시
```bash
# OpenSSL 사용
openssl rand -base64 64

# Python 사용
python -c "import secrets; print(secrets.token_urlsafe(64))"
```

## 📊 볼륨 관리

### 볼륨 목록
- `mariadb_data`: 데이터베이스 데이터
- `redis_data`: Redis 데이터
- `uploads_data`: 업로드된 파일
- `nginx_logs`: Nginx 로그

### 백업
```bash
# 데이터베이스 백업
docker exec myplatform-mariadb mysqldump -u root -p myplatform > backup.sql

# 업로드 파일 백업
docker cp myplatform-backend:/app/uploads ./uploads_backup
```

### 복원
```bash
# 데이터베이스 복원
docker exec -i myplatform-mariadb mysql -u root -p myplatform < backup.sql

# 업로드 파일 복원
docker cp ./uploads_backup myplatform-backend:/app/uploads
```

## 🐛 트러블슈팅

### 컨테이너가 시작되지 않을 때
```bash
# 로그 확인
docker-compose logs backend

# 컨테이너 재생성
docker-compose up -d --force-recreate backend
```

### 데이터베이스 연결 실패
```bash
# MariaDB 헬스체크 확인
docker-compose ps mariadb

# MariaDB 로그 확인
docker-compose logs mariadb

# MariaDB 컨테이너 접속
docker exec -it myplatform-mariadb mysql -u root -p
```

### 포트 충돌
```bash
# 포트 사용 확인 (Windows)
netstat -ano | findstr :8080
netstat -ano | findstr :3306

# 포트 변경 (docker-compose.yml)
ports:
  - "8081:8080"  # 8080 대신 8081 사용
```

## 🔄 업데이트

### 애플리케이션 업데이트
```bash
# 1. 코드 변경 후
# 2. 이미지 재빌드
docker-compose build backend

# 3. 서비스 재시작
docker-compose up -d backend
```

### 데이터베이스 스키마 업데이트
```bash
# 1. SQL 파일 준비
# 2. 컨테이너에 복사
docker cp migration.sql myplatform-mariadb:/tmp/

# 3. 실행
docker exec -it myplatform-mariadb mysql -u root -p myplatform < /tmp/migration.sql
```

## 📈 모니터링

### 리소스 사용량
```bash
# 전체 컨테이너 리소스
docker stats

# 특정 컨테이너
docker stats myplatform-backend
```

### 헬스체크
```bash
# 백엔드 헬스체크
curl http://localhost:8080/actuator/health

# Nginx 헬스체크
curl http://localhost/health
```

## 🎯 최적화 팁

### 이미지 크기 최적화
- Multi-stage build 사용 (이미 적용됨)
- Alpine 베이스 이미지 사용
- 불필요한 의존성 제거

### 성능 최적화
- JVM 메모리 설정: `-XX:MaxRAMPercentage=75.0`
- Nginx 캐싱 설정
- Redis 영구 저장 설정

## 📁 파일 구조

```
my-platform/
├── docker-compose.yml          # Docker Compose 설정
├── Dockerfile                  # 애플리케이션 이미지
├── .env.example               # 환경 변수 예시
├── .env                       # 실제 환경 변수 (gitignore)
├── nginx/
│   ├── nginx.conf            # Nginx 메인 설정
│   └── conf.d/
│       └── default.conf      # 사이트 설정
├── setup-database.sql        # DB 초기화 스크립트
└── insert-default-data.sql   # 기본 데이터
```

## ✅ 체크리스트

배포 전 확인사항:
- [ ] .env 파일 생성 및 비밀번호 설정
- [ ] JWT_SECRET 변경
- [ ] 데이터베이스 비밀번호 변경
- [ ] Redis 비밀번호 설정
- [ ] 방화벽 설정 확인
- [ ] SSL 인증서 설정 (프로덕션)
- [ ] 백업 스크립트 설정
- [ ] 모니터링 설정

