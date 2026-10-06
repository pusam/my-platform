#!/usr/bin/env bash
# =============================================================================
# 동거 사이트 인증서 보장 — deploy.yml(Copy 단계, nginx -t 바로 앞)이 부른다.
#
#   bash scripts/ensure-site-cert.sh <nginx conf 파일명> <도메인>
#   예) bash scripts/ensure-site-cert.sh nsf-news.conf nsf-news.dhkim-lab.duckdns.org
#
# nginx/<conf> 가 docker-compose.yml 에 마운트(비주석 volume 라인)돼 있는데 <도메인> 인증서가 없으면:
#   1) webroot 로 발급한다. 포트 80 ACME 챌린지는 지금 떠 있는 nginx(default.conf 의 default_server)가 처리한다.
#      Let's Encrypt 계정은 이미 있는 것(메인 dhkim-lab 인증서와 같은 계정)을 쓴다 — 약관 동의·메일은 새로 넣지 않는다.
#   2) 그래도 없으면(DNS 미설정·발급 실패) 그 conf 를 이번 배포 동안 주석뿐인 파일로 바꾼다.
#      없는 인증서를 참조한 채로는 nginx -t 가 실패해 배포가 멈추고(다른 사이트 배포까지 막힘),
#      nginx 가 재기동되면 모든 사이트가 내려가기 때문이다. 다음 배포가 원본 conf 를 다시 올리고 발급을 다시 시도한다.
# 발급된 인증서는 certbot 컨테이너(12시간마다 certbot renew)가 다른 인증서와 함께 갱신한다.
# 항상 0 으로 끝난다(판정은 뒤의 nginx -t 가 한다).
# =============================================================================
set -u
cd "$(dirname "$0")/.." || exit 0

conf="${1:?conf 파일명}"
domain="${2:?도메인}"
cert="nginx/ssl/live/$domain/fullchain.pem"

[ -f "nginx/$conf" ] || exit 0
# 마운트 여부 = compose 의 활성(비주석) volume 라인 — deploy.yml 의 seah 가드와 같은 판정
grep -E '^[[:space:]]*-[[:space:]]*\./nginx/' docker-compose.yml | grep -qF "./nginx/$conf:" || exit 0
[ -f "$cert" ] && exit 0

echo "🔐 $domain 인증서가 없어 발급합니다 (webroot)"
docker run --rm \
  -v "$PWD/nginx/ssl:/etc/letsencrypt" \
  -v "$PWD/nginx/certbot-webroot:/var/www/certbot" \
  --entrypoint sh certbot/certbot:latest -c '
    account=$(sed -n "s/^account = //p" /etc/letsencrypt/renewal/dhkim-lab.duckdns.org.conf 2>/dev/null | head -n 1)
    exec certbot certonly --webroot -w /var/www/certbot -d "$1" --non-interactive ${account:+--account "$account"}
  ' sh "$domain" </dev/null || true

if [ -f "$cert" ]; then
  echo "✅ $domain 인증서 발급됨"
else
  printf '# %s 인증서가 아직 없어 이번 배포에서는 이 사이트를 끈다.\n# scripts/ensure-site-cert.sh — 다음 배포가 원본을 다시 올리고 발급을 다시 시도한다.\n' "$domain" > "nginx/$conf"
  echo "⚠️ $domain 인증서 발급 실패 — 위 certbot 로그 확인. 이번 배포는 nginx/$conf 를 끄고 진행(다른 사이트는 그대로)."
fi
exit 0
