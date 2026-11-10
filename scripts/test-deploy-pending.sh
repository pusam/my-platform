#!/usr/bin/env bash
# deploy-pending.sh 판정 표 (2026-11-10). CI preflight 잡이 일정 실행에서 판정 전에 먼저 돌린다.
# 임시 git 저장소를 만들어 커밋을 쌓고 판정한다. 로컬: bash scripts/test-deploy-pending.sh
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/deploy-pending.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0
count=0

cd "$TMP"
git init -q -b main .
git config user.email t@example.invalid
git config user.name t
git config commit.gpgsign false
commit() { git commit -q --allow-empty -m "$1"; git rev-parse HEAD; }

# check <설명> <서버 커밋> <main 끝> <기대 pending 또는 exit:N>
check() {
  local label="$1" server="$2" head="$3" want="$4" out code got
  count=$((count + 1))
  out=$(bash "$SCRIPT" "$server" "$head" 2>&1); code=$?
  if [ $code -ne 0 ]; then got="exit:$code"; else got="$(printf '%s\n' "$out" | sed -n 's/^pending=//p')"; fi
  if [ "$got" = "$want" ]; then
    echo "ok    $label → $got"
  else
    echo "FAIL  $label → $got (기대 $want)"
    printf '%s\n' "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

base=$(commit "feat: 배포된 커밋")
check "서버 = main 끝" "$base" "$base" false

s1=$(commit "docs: 문서 [skip ci]")
s2=$(commit "chore: 설정 [ci skip]")
s3=$(commit "docs: [no ci] 메모")
s4=$(commit "docs: [skip actions]")
s5=$(commit "docs: 본문에 표시

[actions skip]")
check "밀린 커밋이 전부 건너뛰기 표시(5종)" "$base" "$s5" false

n1=$(commit "fix: 장중에 보류된 수정")
check "건너뛰기 표시 + 표시 없는 커밋 하나" "$base" "$n1" true
check "표시 없는 커밋만" "$s5" "$n1" true
s6=$(commit "docs: 그 뒤 문서 [skip ci]")
check "표시 없는 커밋이 중간에 묻혀 있어도" "$base" "$s6" true
check "대소문자가 다른 표시는 GitHub 표시가 아니다" "$n1" "$(commit 'docs: [Skip CI]')" true

git checkout -q -b side "$base"
side=$(commit "feat: 다른 브랜치 커밋")
git checkout -q main
check "서버 커밋이 main 의 조상이 아님" "$side" "$n1" "exit:3"
check "서버 커밋을 이력에서 못 찾음" "0123456789abcdef0123456789abcdef01234567" "$n1" "exit:3"
check "서버 커밋이 빔" "" "$n1" "exit:2"

echo "---- $count건 중 실패 $fail건"
[ "$fail" -eq 0 ]
