#!/usr/bin/env bash
# backup-db.sh 테스트 — 가짜 docker(덤프 출력 흉내), 임시 HOME. 실제 DB·도커에 닿지 않는다.
# 실행: bash scripts/tests/backup-db.test.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUT="$ROOT/scripts/backup-db.sh"
PASS=0; FAIL=0
check() { local d="$1"; shift; if "$@"; then PASS=$((PASS+1)); echo "  ok   $d"; else FAIL=$((FAIL+1)); echo "  FAIL $d"; cat "$T/out.log" | sed 's/^/       /'; fi; }

setup() {
  T="$(mktemp -d)"; mkdir -p "$T/bin" "$T/home/backups"
  cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
# 압축해도 1MB 를 넘는 덤프 본문 + 모드별 끝맺음
echo '-- Current Database: `myplatform`'
head -c 1500000 /dev/urandom | base64
case "$FAKE_MODE" in
  ok) echo '-- Dump completed on 2026-11-10  3:00:41' ;;
  died) echo 'INSERT INTO a VALUES (1),'; exit 2 ;;
  nocomplete) echo 'INSERT INTO a VALUES (1);' ;;
esac
EOF
  chmod +x "$T/bin/docker"
  touch -d '2026-09-01' "$T/home/backups/db-20260901-0300.sql.gz"   # 30일 넘은 옛 백업
}
run() { env HOME="$T/home" PATH="$T/bin:$PATH" FAKE_MODE="$1" bash "$SUT" > "$T/out.log" 2>&1; RC=$?; }
count_new() { ls "$T/home/backups" | grep -vc '^db-20260901' ; }

echo "■ 정상 덤프 → 성공 · 옛 백업 정리"
setup; run ok
check "종료코드 0" test "$RC" -eq 0
check "새 백업 1개" test "$(count_new)" -eq 1
check "30일 넘은 백업 정리" test ! -e "$T/home/backups/db-20260901-0300.sql.gz"
rm -rf "$T"

echo "■ 덤프가 중간에 죽음(1MB 넘게 썼어도) → 실패 · 파일 삭제 · 옛 백업 유지"
setup; run died
check "종료코드 1" test "$RC" -eq 1
check "새 백업 없음" test "$(count_new)" -eq 0
check "옛 백업 그대로" test -e "$T/home/backups/db-20260901-0300.sql.gz"
rm -rf "$T"

echo "■ 종료코드는 0 인데 끝맺음 줄이 없음 → 실패"
setup; run nocomplete
check "종료코드 1" test "$RC" -eq 1
check "새 백업 없음" test "$(count_new)" -eq 0
rm -rf "$T"

echo; echo "결과: 통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
