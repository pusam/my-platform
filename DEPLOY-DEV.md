# 개발 서버 배포 가이드

## 🌐 서버 정보

- **호스트**: 218.152.9.119
- **SSH 포트**: 9922
- **사용자**: dev
- **SSH 키**: ~/.ssh/id_ed25519
- **배포 경로**: /home/dev/my-platform

## 🚀 자동 배포

### Windows
```bash
deploy-dev.bat
```

### Linux/Mac
```bash
chmod +x deploy-dev.sh
./deploy-dev.sh
```

## 📋 수동 배포

### 1. 로컬 빌드
```bash
docker-compose build
```

### 2. 이미지 저장
```bash
docker save myplatform-backend:latest | gzip > myplatform-backend.tar.gz
```

### 3. 서버로 전송
```bash
scp -P 9922 -i ~/.ssh/id_ed25519 \
    myplatform-backend.tar.gz \
    docker-compose.yml \
    .env \
    setup-database.sql \
    insert-default-data.sql \
    dev@218.152.9.119:/home/dev/my-platform/

scp -P 9922 -i ~/.ssh/id_ed25519 -r \
    nginx \
    dev@218.152.9.119:/home/dev/my-platform/
```

### 4. 서버 접속 및 실행
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119

cd /home/dev/my-platform

# 이미지 로드
docker load < myplatform-backend.tar.gz

# 기존 컨테이너 중지
docker-compose down

# 새 컨테이너 시작
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f
```

## 🔧 서버 관리

### SSH 접속
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119
```

### 로그 확인
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "cd /home/dev/my-platform && docker-compose logs -f"
```

### 컨테이너 재시작
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "cd /home/dev/my-platform && docker-compose restart"
```

### 컨테이너 중지
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "cd /home/dev/my-platform && docker-compose down"
```

## 📊 모니터링

### 상태 확인
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "cd /home/dev/my-platform && docker-compose ps"
```

### 리소스 사용량
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "docker stats"
```

### 헬스체크
```bash
# Nginx
curl http://218.152.9.119/health

# Backend
curl http://218.152.9.119:8080/actuator/health
```

## 🔐 환경 변수

서버에 .env 파일이 필요합니다:

```bash
# 서버에서
cd /home/dev/my-platform
nano .env
```

필수 설정:
```env
MYSQL_ROOT_PASSWORD=강력한_비밀번호
MYSQL_PASSWORD=강력한_비밀번호
REDIS_PASSWORD=강력한_비밀번호
JWT_SECRET=최소_256비트_랜덤_문자열
SPRING_PROFILES_ACTIVE=prod
```

## 🐛 트러블슈팅

### 포트 확인
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "netstat -tulpn | grep -E '80|8080|3306|6379'"
```

### Docker 상태 확인
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "docker ps -a"
```

### 로그 다운로드
```bash
scp -P 9922 -i ~/.ssh/id_ed25519 \
    dev@218.152.9.119:/home/dev/my-platform/nginx_logs/* \
    ./logs/
```

## 📦 백업

### 데이터베이스 백업
```bash
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 \
    "docker exec myplatform-mariadb mysqldump -u root -p myplatform > /tmp/backup.sql"

scp -P 9922 -i ~/.ssh/id_ed25519 \
    dev@218.152.9.119:/tmp/backup.sql \
    ./backup-$(date +%Y%m%d).sql
```

### 업로드 파일 백업
```bash
scp -P 9922 -i ~/.ssh/id_ed25519 -r \
    dev@218.152.9.119:/home/dev/my-platform/uploads \
    ./uploads-backup-$(date +%Y%m%d)
```

## 🔄 업데이트 워크플로우

1. 로컬에서 코드 수정
2. Git commit & push
3. `deploy-dev.bat` 실행 (또는 `deploy-dev.sh`)
4. 서버 로그 확인
5. 브라우저에서 http://218.152.9.119 접속 테스트

## 📍 접속 URL

- 애플리케이션: http://218.152.9.119
- Swagger API: http://218.152.9.119/swagger-ui/index.html
- 백엔드 직접: http://218.152.9.119:8080

## ⚡ 빠른 명령어

```bash
# 배포
deploy-dev.bat

# 로그 실시간 확인
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 "cd /home/dev/my-platform && docker-compose logs -f backend"

# 재시작
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 "cd /home/dev/my-platform && docker-compose restart backend"

# 중지
ssh -p 9922 -i ~/.ssh/id_ed25519 dev@218.152.9.119 "cd /home/dev/my-platform && docker-compose down"
```

