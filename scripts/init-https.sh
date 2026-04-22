#!/usr/bin/env bash
# =============================================================================
# Let's Encrypt 인증서 최초 발급 스크립트
#
# 동작:
#   1. 더미(self-signed) 인증서를 생성해서 nginx 가 일단 뜨게 함
#   2. nginx 시작 (HTTPS 응답은 가짜지만 80 포트는 정상)
#   3. 더미 인증서 삭제
#   4. certbot 으로 진짜 Let's Encrypt 인증서 발급 (--webroot)
#   5. nginx reload 로 진짜 인증서 적용
#
# 사용:
#   sudo bash scripts/init-https.sh
#
# 사전 조건:
#   - 도메인이 이 서버 공인 IP 로 정상 해석되어야 함 (DDNS 동작 확인)
#   - 공유기에서 80, 443 포트가 이 서버로 포트포워딩 되어 있어야 함
#   - docker / docker compose 설치 완료
# =============================================================================

set -euo pipefail

# ---- 설정 ----
DOMAIN="${DOMAIN:-dhkim-lab.duckdns.org}"
EMAIL="${EMAIL:-kdhgla@gmail.com}"
RSA_KEY_SIZE=4096
DATA_PATH="./nginx/ssl"
WEBROOT="./nginx/certbot-webroot"
# 운영 발급 / 테스트 발급(테스트로 횟수 제한 회피하려면 1)
STAGING="${STAGING:-0}"

if [ "$EUID" -ne 0 ]; then
  echo "❌ root 로 실행해주세요 (sudo bash scripts/init-https.sh)"
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "❌ docker 가 필요합니다."
  exit 1
fi

DC="docker compose"
if ! $DC version >/dev/null 2>&1; then
  DC="docker-compose"
fi

echo "▶ 도메인: $DOMAIN"
echo "▶ 이메일: $EMAIL"
echo "▶ 모드: $([ "$STAGING" = "1" ] && echo "STAGING(테스트)" || echo "PRODUCTION(실제 발급)")"
echo

mkdir -p "$DATA_PATH/live/$DOMAIN" "$WEBROOT"

# ---- 1. 더미 인증서 생성 (nginx 부팅용) ----
echo "▶ 1/5  더미 인증서 생성 중 ..."
docker run --rm -v "$(pwd)/$DATA_PATH:/etc/letsencrypt" \
  --entrypoint sh certbot/certbot \
  -c "openssl req -x509 -nodes -newkey rsa:$RSA_KEY_SIZE \
        -days 1 \
        -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
        -out    '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
        -subj '/CN=localhost'"

# ---- 2. nginx 만 부팅 ----
echo "▶ 2/5  nginx 기동 중 ..."
$DC up -d nginx
sleep 3

# ---- 3. 더미 인증서 삭제 ----
echo "▶ 3/5  더미 인증서 제거 중 ..."
docker run --rm -v "$(pwd)/$DATA_PATH:/etc/letsencrypt" \
  --entrypoint sh certbot/certbot \
  -c "rm -rf /etc/letsencrypt/live/$DOMAIN \
              /etc/letsencrypt/archive/$DOMAIN \
              /etc/letsencrypt/renewal/$DOMAIN.conf"

# ---- 4. 진짜 인증서 발급 ----
echo "▶ 4/5  Let's Encrypt 인증서 발급 중 ..."
STAGING_FLAG=""
[ "$STAGING" = "1" ] && STAGING_FLAG="--staging"

docker run --rm \
  -v "$(pwd)/$DATA_PATH:/etc/letsencrypt" \
  -v "$(pwd)/$WEBROOT:/var/www/certbot" \
  certbot/certbot certonly \
    --webroot -w /var/www/certbot \
    --email "$EMAIL" \
    -d "$DOMAIN" \
    --rsa-key-size "$RSA_KEY_SIZE" \
    --agree-tos \
    --non-interactive \
    --no-eff-email \
    --force-renewal \
    $STAGING_FLAG

# ---- 5. nginx reload ----
echo "▶ 5/5  nginx 리로드 중 ..."
$DC exec nginx nginx -s reload

echo
echo "✅ 완료! 다음 URL 로 접속 확인:"
echo "    https://$DOMAIN/"
echo
echo "이후 docker-compose 의 certbot 컨테이너가 12시간마다 자동 갱신합니다."
