#!/usr/bin/env bash
# deploy-window.sh 경계 시각 표 (2026-11-10). CI 의 deploy-gate 잡이 판정 전에 먼저 돌린다 — 여기서 하나라도 틀리면
# 그 실행은 배포하지 않는다(가드가 고장 나면 막는 쪽). 로컬: bash scripts/test-deploy-window.sh
#
# 시각은 UTC 문자열로 적고 옆에 한국 시각을 단다 — 러너가 UTC 라서 'UTC 날짜·요일 ≠ 한국 날짜·요일'인 경계가 중요하다.
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/deploy-window.sh"
fail=0
count=0

utc_epoch() { date -u -d "$1" +%s; }

# check <UTC 시각> <기대 decision> <기대 wait_seconds> <설명> [DEPLOY_MESSAGES] [FORCE_DEPLOY] [TZ]
check() {
  local utc="$1" want="$2" want_wait="$3" label="$4" msgs="${5:-}" force="${6:-false}" tz="${7:-UTC}"
  local out decision wait_s
  count=$((count + 1))
  out=$(TZ="$tz" DEPLOY_MESSAGES="$msgs" FORCE_DEPLOY="$force" bash "$SCRIPT" "$(utc_epoch "$utc")") || {
    echo "FAIL  $label — 스크립트가 실패로 끝났다"; fail=$((fail + 1)); return
  }
  decision=$(printf '%s\n' "$out" | sed -n 's/^decision=//p')
  wait_s=$(printf '%s\n' "$out" | sed -n 's/^wait_seconds=//p')
  if [ "$decision" = "$want" ] && [ "$wait_s" = "$want_wait" ]; then
    echo "ok    $label → $decision${wait_s:+ ($wait_s s)}"
  else
    echo "FAIL  $label → $decision/$wait_s (기대 $want/$want_wait)"
    printf '%s\n' "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

# --- 보류 창 08:20~16:25 (평일) ---  2026-11-11 = 수요일
check "2026-11-10 23:19:59" deploy 0 "수 08:19:59 KST"
check "2026-11-10 23:20:00" defer  0 "수 08:20 KST (UTC 는 아직 화요일)"
check "2026-11-11 03:00:00" defer  0 "수 12:00 KST"
check "2026-11-11 07:25:59" defer  0 "수 16:25:59 KST"
check "2026-11-11 07:26:00" deploy 0 "수 16:26 KST"
check "2026-11-11 07:27:00" deploy 0 "수 16:27 KST (자동 배포 시각)"

# --- 기다림 16:50~17:03 (평일, 17:00 스냅샷) ---
check "2026-11-11 07:49:59" deploy 0   "수 16:49:59 KST"
check "2026-11-11 07:50:00" wait   780 "수 16:50 KST → 17:03 까지 13분"
check "2026-11-11 08:02:00" wait   60  "수 17:02 KST"
check "2026-11-11 08:02:59" wait   1   "수 17:02:59 KST"
check "2026-11-11 08:03:00" deploy 0   "수 17:03 KST"

# --- 주말·요일 경계 (UTC 와 한국의 요일이 다른 시각) ---
check "2026-11-14 01:00:00" deploy 0 "토 10:00 KST"
check "2026-11-14 07:55:00" deploy 0 "토 16:55 KST — 주말엔 기다림 없음"
check "2026-11-15 03:00:00" deploy 0 "일 12:00 KST"
check "2026-11-15 23:20:00" defer  0 "월 08:20 KST (UTC 는 일요일)"
check "2026-11-13 23:30:00" deploy 0 "토 08:30 KST (UTC 는 금요일)"
check "2026-11-13 07:00:00" defer  0 "금 16:00 KST"
check "2026-11-12 14:00:00" deploy 0 "목 23:00 KST"

# --- 표시·수동 실행은 보류와 기다림을 건너뛴다 ---
check "2026-11-11 03:00:00" deploy 0 "장중 + [deploy-now]" "fix: 급한 수정 [deploy-now]"
check "2026-11-11 07:55:00" deploy 0 "기다림 + [deploy-now]" "a
b [deploy-now]"
check "2026-11-11 03:00:00" defer  0 "장중 + 비슷한 글자는 표시가 아니다" "deploy-now 아님 [deploy now]"
check "2026-11-11 03:00:00" deploy 0 "장중 + 수동 실행 deploy_now" "" "true"

# --- 러너 시간대와 무관 ---
check "2026-11-10 23:20:00" defer  0 "TZ=America/New_York 에서도 같은 판정" "" "false" "America/New_York"
check "2026-11-10 23:19:00" deploy 0 "TZ=Asia/Seoul 에서도 같은 판정"    "" "false" "Asia/Seoul"

# --- 잘못된 입력은 실패(호출 쪽이 막음으로 다룬다) ---
count=$((count + 1))
if bash "$SCRIPT" "abc" >/dev/null 2>&1; then
  echo "FAIL  숫자가 아닌 시각을 받아들였다"; fail=$((fail + 1))
else
  echo "ok    숫자가 아닌 시각 → 실패"
fi

echo "---- $count건 중 실패 $fail건"
[ "$fail" -eq 0 ]
