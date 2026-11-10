#!/usr/bin/env bash
# 일정 실행(평일 16:27·17:12)이 배포할 것이 있는가 (2026-11-10, 개선 후보 4번)
#
# 서버에 떠 있는 백엔드 커밋(이미지 라벨) .. main 끝 사이에 '배포돼야 할' 커밋이 하나라도 있으면 pending=true.
#   - 같은 커밋이면 false(매일 이유 없이 재시작하지 않는다).
#   - 사이의 커밋이 **전부** GitHub 건너뛰기 표시([skip ci]·[ci skip]·[no ci]·[skip actions]·[actions skip])를 달았으면 false —
#     그 표시는 push 실행에만 적용돼, 그렇게 올린 문서·설정 커밋이 일정 실행으로 배포·재시작되지 않게.
#   - 표시 없는 커밋이 하나라도 밀려 있으면 true — 보류된 배포를 잊지 않는다.
#   - 서버 커밋이 main 끝의 조상이 아니면(모르는 커밋·다른 브랜치) 판단하지 않고 실패(exit 3) — 무엇을 배포하는지 모르는 채 덮지 않는다.
#
# 입력 : $1 = 서버 백엔드 커밋, $2 = main 끝 커밋(기본 HEAD). 저장소 전체 이력이 있어야 한다(checkout fetch-depth: 0).
# 출력 : pending=true|false / reason=…
# 종료 : 0 = 판정함, 2 = 입력 없음, 3 = 서버 커밋을 이력에서 못 찾거나 조상이 아님
set -euo pipefail

server="${1:-}"
head="${2:-HEAD}"
if [ -z "$server" ]; then
  echo "deploy-pending: 서버 커밋이 비었다" >&2
  exit 2
fi
if ! git cat-file -e "${server}^{commit}" 2>/dev/null; then
  echo "deploy-pending: 서버 커밋 $server 을 저장소 이력에서 못 찾았다" >&2
  exit 3
fi
head_sha="$(git rev-parse "$head")"
server_sha="$(git rev-parse "$server")"
if [ "$server_sha" = "$head_sha" ]; then
  echo "pending=false"
  echo "reason=서버가 이미 main 끝이다"
  exit 0
fi
if ! git merge-base --is-ancestor "$server_sha" "$head_sha"; then
  echo "deploy-pending: 서버 커밋 ${server_sha:0:8} 이 main 끝 ${head_sha:0:8} 의 조상이 아니다" >&2
  exit 3
fi

total=0
needs=0
first_needed=""
for c in $(git rev-list "$server_sha..$head_sha"); do
  total=$((total + 1))
  msg="$(git log -1 --format=%B "$c")"
  case "$msg" in
    *"[skip ci]"*|*"[ci skip]"*|*"[no ci]"*|*"[skip actions]"*|*"[actions skip]"*) ;;
    *) needs=$((needs + 1)); first_needed="${c:0:8}" ;;
  esac
done

if [ "$needs" -eq 0 ]; then
  echo "pending=false"
  echo "reason=밀린 커밋 ${total}개가 전부 건너뛰기 표시 — 배포하지 않는다"
else
  echo "pending=true"
  echo "reason=밀린 커밋 ${total}개 중 ${needs}개가 배포 대상(가장 오래된 것 ${first_needed})"
fi
