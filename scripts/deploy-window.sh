#!/usr/bin/env bash
# 배포 시각 판정 — 지금 백엔드를 재시작해도 되는가 (2026-11-10, 개선 후보 4번)
#
# 왜: main 푸시는 자동 배포 = 백엔드 재시작이다. 장중(재무 수집 08:30~·수급 15:50·16:15 확정치)에 재시작하면 그날은 추천 성적
#     표본 시작일이 될 수 없고 수집 회차가 끊긴다(CLAUDE.md §7). 2026년 가을 장중 푸시로 표본 시작일이 네 번 밀렸다.
#
# 판정(한국 시각, 평일만 — 한국 휴장일은 모른다. 휴장일 장중 푸시는 보류됐다가 16:27 자동 배포로 나간다):
#   defer : 08:20 <= 시각 <= 16:25  → 지금은 배포하지 않는다(16:27 자동 배포 또는 수동 실행으로 나간다)
#   wait  : 16:50 <= 시각 <  17:03  → 17:00 스냅샷(시그널 기록 회차) 위에 재시작이 떨어지지 않게 17:03 까지 기다린 뒤 다시 판정
#   deploy: 그 밖, 또는 커밋 메시지에 [deploy-now] 가 있거나 FORCE_DEPLOY=true
# ⚠ 장이 늦게 닫히는 날(2026-11-19 수능일, 16:30 마감)도 창은 16:25 에 끝난다 — 그날은 17:10 전에 푸시하지 않는다.
#
# 입력 : $1 = 판정할 시각(유닉스 초, 생략하면 지금) · env DEPLOY_MESSAGES(이번 푸시의 커밋 메시지들) · env FORCE_DEPLOY
# 출력 : decision=… / wait_seconds=… / kst=… / reason=…  (GITHUB_OUTPUT 에 그대로 붙일 수 있는 key=value 줄)
# 종료 : 0 = 판정함, 2 = 입력이 잘못됨(호출 쪽은 이것을 '막음'으로 다룬다)
#
# 한국 시각은 TZ 데이터베이스 없이 계산한다 — UTC 초 + 9시간을 date -u 로 읽는다(한국은 서머타임이 없다). 러너의 TZ 설정이나
# tzdata 유무에 따라 결과가 달라지지 않게(TZ=Asia/Seoul 은 데이터가 없으면 조용히 UTC 로 떨어진다).
set -euo pipefail

DEFER_FROM=820     # 08:20
DEFER_TO=1625      # 16:25 (포함)
WAIT_FROM=1650     # 16:50
WAIT_UNTIL=1703    # 17:03 (미포함)
MARKER='[deploy-now]'

epoch="${1:-$(date -u +%s)}"
if ! [[ "$epoch" =~ ^[0-9]+$ ]]; then
  echo "deploy-window: 시각이 숫자가 아니다: '$epoch'" >&2
  exit 2
fi

kst_epoch=$((epoch + 9 * 3600))
read -r kst_date kst_time dow hhmm < <(date -u -d "@$kst_epoch" '+%Y-%m-%d %H:%M:%S %u %H%M') || {
  echo "deploy-window: date 계산 실패" >&2
  exit 2
}
if [ -z "${hhmm:-}" ] || [ -z "${dow:-}" ]; then
  echo "deploy-window: date 출력을 읽지 못했다" >&2
  exit 2
fi
now_hm=$((10#$hhmm))
days=(_ 월 화 수 목 금 토 일)
kst_label="$kst_date $kst_time (${days[$dow]}) KST"

emit() {
  echo "decision=$1"
  echo "wait_seconds=$2"
  echo "kst=$kst_label"
  echo "reason=$3"
}

if [ "${FORCE_DEPLOY:-false}" = "true" ]; then
  emit deploy 0 "수동 실행 deploy_now — 시각과 무관하게 지금 배포"
  exit 0
fi
case "${DEPLOY_MESSAGES:-}" in
  *"$MARKER"*)
    emit deploy 0 "커밋 메시지의 $MARKER — 시각과 무관하게 지금 배포"
    exit 0
    ;;
esac

if [ "$dow" -ge 6 ]; then
  emit deploy 0 "주말 — 바로 배포"
  exit 0
fi

if [ "$now_hm" -ge "$DEFER_FROM" ] && [ "$now_hm" -le "$DEFER_TO" ]; then
  emit defer 0 "평일 08:20~16:25 장중 — 백엔드를 재시작하지 않는다(평일 16:27 자동 배포 또는 Actions 에서 수동 실행 deploy_now)"
  exit 0
fi

if [ "$now_hm" -ge "$WAIT_FROM" ] && [ "$now_hm" -lt "$WAIT_UNTIL" ]; then
  h=$((10#${kst_time:0:2})); m=$((10#${kst_time:3:2})); s=$((10#${kst_time:6:2}))
  wait_s=$(( (17 * 3600 + 3 * 60) - (h * 3600 + m * 60 + s) ))
  emit wait "$wait_s" "평일 16:50~17:03 — 17:00 스냅샷(시그널 기록) 뒤로 ${wait_s}초 기다린 뒤 다시 판정"
  exit 0
fi

emit deploy 0 "장 밖 — 바로 배포"
