#!/usr/bin/env bash
#
# CLAUDE.md(Claude Code) ↔ AGENTS.md(Codex) 동기화 가드.
#
# 왜 필요한가: 두 도구가 각자 자기 파일명만 읽으므로 사본을 없앨 수 없다. 그래서 "사본을 두되
# 어긋나면 시끄럽게" 한다 — 한쪽에만 불변식이 들어가면 다른 도구는 그걸 모른 채 같은 코드를
# 되돌린다. 실제로 2026-09-08 토큰 방어(EGW00123)가 두 파일 다 빠져 있었고, 그건 "KIS 는 틀린
# 요청에도 200/500 을 준다"는 §4c 계열 함정이라 모르면 401 단독 판정으로 되돌리기 쉽다.
#
# 규약: 제목(1번째 줄)만 도구별로 다르고, 2번째 줄부터는 완전히 같아야 한다.
#
# 로컬 실행: bash scripts/check-agent-instructions-sync.sh
# 어긋났을 때: 최신 쪽을 상대 파일로 복사한 뒤 제목 줄만 각자 것으로 되돌린다.

set -euo pipefail
cd "$(dirname "$0")/.."

for f in CLAUDE.md AGENTS.md; do
    if [ ! -f "$f" ]; then
        echo "❌ $f 가 없다 — 두 도구의 지침은 둘 다 저장소에 있어야 한다(한쪽만 있으면 다른 도구가 무지침으로 돈다)"
        exit 1
    fi
done

if diff -u --label "CLAUDE.md" --label "AGENTS.md" \
        <(tail -n +2 CLAUDE.md) <(tail -n +2 AGENTS.md); then
    echo "✅ CLAUDE.md ↔ AGENTS.md 동기화 정상 (제목 줄 제외 동일)"
    exit 0
fi

cat <<'MSG'

❌ CLAUDE.md 와 AGENTS.md 가 어긋났다 (위 diff — 왼쪽 CLAUDE.md / 오른쪽 AGENTS.md).

둘은 같은 작업 지침의 도구별 사본이다. 한쪽에만 불변식이 들어가면 다른 도구가 그것을 모른 채
코드를 되돌린다. 최신 쪽을 기준으로 맞춘 뒤 제목 줄만 각자 것으로 남길 것:

  1번째 줄 CLAUDE.md : # 주식 플랫폼 — Claude Code 작업 지침
  1번째 줄 AGENTS.md : # 주식 플랫폼 — Codex 작업 지침

예) CLAUDE.md 가 최신이면:
  tail -n +2 CLAUDE.md > /tmp/body && \
    { echo '# 주식 플랫폼 — Codex 작업 지침'; cat /tmp/body; } > AGENTS.md
MSG
exit 1
