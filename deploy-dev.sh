#!/bin/bash
# 개발 서버 배포 스크립트
# 사용 전 deploy-config.sh 파일을 생성하세요 (deploy-config.sh.example 참고)

set -e

echo "🚀 개발 서버 배포 시작..."
echo "================================"

# 설정 파일 로드
if [ ! -f "deploy-config.sh" ]; then
    echo "❌ deploy-config.sh 파일이 없습니다!"
    echo "📝 deploy-config.sh.example 파일을 복사하여 deploy-config.sh를 생성하세요."
    exit 1
fi
source deploy-config.sh

# 필수 변수 확인
if [ -z "$SERVER_USER" ] || [ -z "$SERVER_HOST" ]; then
    echo "❌ 서버 정보가 설정되지 않았습니다!"
    exit 1
fi

# 1. 로컬 빌드
echo "📦 로컬에서 Docker 이미지 빌드 중..."
docker-compose build

# 2. 이미지 저장
echo "💾 Docker 이미지를 tar 파일로 저장 중..."
docker save myplatform-backend:latest | gzip > myplatform-backend.tar.gz

# 3. 서버로 전송
echo "📤 서버로 파일 전송 중..."
scp -P $SERVER_PORT -i $SSH_KEY \
    myplatform-backend.tar.gz \
    docker-compose.yml \
    .env \
    setup-database.sql \
    insert-default-data.sql \
    ${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}/

# Nginx 설정 전송
scp -P $SERVER_PORT -i $SSH_KEY -r \
    nginx \
    ${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}/

# 4. 서버에서 실행
echo "🐳 서버에서 Docker 컨테이너 실행 중..."
ssh -p $SERVER_PORT -i $SSH_KEY ${SERVER_USER}@${SERVER_HOST} << 'ENDSSH'
    cd $REMOTE_DIR
    # 이미지 로드
    echo "📥 Docker 이미지 로드 중..."
    docker load < myplatform-backend.tar.gz

    # 기존 컨테이너 중지
    echo "🛑 기존 컨테이너 중지 중..."
    docker-compose down

    # 새 컨테이너 시작
    echo "✅ 새 컨테이너 시작 중..."
    docker-compose up -d

    # 상태 확인
    echo "📊 컨테이너 상태 확인..."
    docker-compose ps

    # 로그 확인
    echo "📋 최근 로그 (20줄)..."
    docker-compose logs --tail=20

    # tar 파일 정리
    rm -f myplatform-backend.tar.gz
ENDSSH

# 5. 로컬 정리
echo "🧹 로컬 파일 정리..."
rm -f myplatform-backend.tar.gz

echo ""
echo "================================"
echo "✅ 배포 완료!"
echo ""
echo "🌐 서버 URL: $SERVER_URL"
echo "📚 Swagger: $SERVER_URL/swagger-ui/index.html"
echo "📊 로그 확인: ssh -p $SERVER_PORT -i $SSH_KEY $SERVER_USER@$SERVER_HOST 'cd $REMOTE_DIR && docker-compose logs -f'"