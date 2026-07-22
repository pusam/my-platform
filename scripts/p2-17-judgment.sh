#!/bin/bash
# ============================================================================
# P2-17 ATR 세트(×2.5 청산 + 리스크 균등 사이징) REAL 확장 판정 — 읽기 전용
#
# 실행: 서버 프로젝트 루트에서  bash scripts/p2-17-judgment.sh
# 판정 기준(VERIFICATION_BACKLOG P2-17 / SCHEDULE_DECISIONS 2026-07-22):
#   ① VIRTUAL 2주+ 실측에서 고정 -3/+5 대비 동수익 이상
#   ② 일일 손실 브레이커 발동 동수 이하
#   ③ ATR 사이징 수량이 현행 이하(PositionSizer 계약 위반 0건)
# 셋 다 충족 → REAL 확장 "검토" 가능(그때도 단계적). 미달 → VIRTUAL 유지.
# ⚠ 이 스크립트는 판정 재료를 모아줄 뿐, 최종 결정은 사람이 한다.
#   특히 ①의 비교창(직전 동일길이)은 시장 국면이 다를 수 있음 — 수치만 보고 기계적 판단 금지.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

dbq() { docker compose exec -T mariadb sh -c \
  'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot -N -e "$1"' _ "$1"; }

DB=myplatform
SELL_REASONS="'STOP_LOSS','TAKE_PROFIT','TAKE_PROFIT_HALF','TRAILING_STOP','TIME_CUT','END_OF_DAY','AUTO_SELL','SCALPING_CLEARANCE','REGULAR_SESSION_CLOSE','NXT_SESSION_CLOSE','GAP_DOWN_EXIT','EARLY_EXIT'"

echo "==================================================================="
echo " P2-17 ATR 세트 판정 리포트  ($(date '+%Y-%m-%d %H:%M'))"
echo "==================================================================="

# ---------- 0) 전제: 실측이 실제로 시작됐는가 ----------
START=$(dbq "SELECT MIN(created_at) FROM $DB.trading_audit_log WHERE triggered_by='ATR_SIZING';")
if [ -z "$START" ] || [ "$START" = "NULL" ]; then
  echo ""
  echo "[판정 불가] ATR_SIZING 감사 행이 0건 — 플래그(BOT_ATR_TRADING_ENABLED)가 켜진 적이 없거나"
  echo "            켠 뒤 VIRTUAL 스윙/스캘핑 매수가 한 번도 발생하지 않았습니다."
  echo "            → OPS_CHECKLIST_2026-07.md §2 절차로 ON 후 2주 재측정. 오늘 판정은 연기."
  exit 0
fi
DAYS=$(dbq "SELECT DATEDIFF(NOW(), '$START');")
echo ""
echo "[0] 실측 시작: $START  (경과 ${DAYS}일)"
if [ "$DAYS" -lt 14 ]; then
  echo "    ⚠ 2주(14일) 미만 — 아래 수치는 참고만, 판정은 $(dbq "SELECT DATE_ADD('$START', INTERVAL 14 DAY);") 이후 권장."
fi

ACCOUNT=$(dbq "SELECT id FROM $DB.virtual_account WHERE is_active=1 ORDER BY id DESC LIMIT 1;")
echo "    활성 VIRTUAL 계좌 id: ${ACCOUNT:-없음}"
[ -z "$ACCOUNT" ] && { echo "[판정 불가] 활성 가상계좌 없음"; exit 0; }

# ---------- ① 성과: ON 창 vs 직전 동일길이 창 (봇 SELL 실현손익) ----------
echo ""
echo "[①] 실현손익 비교 — ON 창([시작,현재]) vs 직전 동일길이 창 (VIRTUAL 봇 SELL 확정분)"
Q_PERF="SELECT
  CASE WHEN trade_date >= '$START' THEN 'ON(ATR)' ELSE 'BASE(고정-3/+5)' END AS win,
  COUNT(*) AS trades,
  ROUND(SUM(profit_loss),0) AS pnl_sum,
  ROUND(AVG(profit_loss),0) AS pnl_avg,
  CONCAT(ROUND(100*SUM(profit_loss>0)/COUNT(*),1),'%') AS win_rate
 FROM $DB.virtual_trade_history
 WHERE account_id=$ACCOUNT AND trade_type='SELL' AND profit_loss IS NOT NULL
   AND trade_reason IN ($SELL_REASONS)
   AND trade_date >= DATE_SUB('$START', INTERVAL $DAYS DAY)
 GROUP BY win ORDER BY win;"
dbq "$Q_PERF" | sed 's/^/    /'
echo "    (기준: ON pnl_sum ≥ BASE pnl_sum 이면 '동수익 이상'. 창 간 시장 국면 차이는 사람이 감안)"

# ---------- ② 일일 손실 브레이커 발동 수 비교 ----------
echo ""
echo "[②] 일일 손실 브레이커 발동 — ON 창 vs 직전 동일길이 창 (동수 이하여야 충족)"
Q_BRK="SELECT
  CASE WHEN created_at >= '$START' THEN 'ON(ATR)' ELSE 'BASE' END AS win,
  COUNT(*) AS trips
 FROM $DB.trading_audit_log
 WHERE triggered_by='DAILY_LOSS_BREAKER'
   AND created_at >= DATE_SUB('$START', INTERVAL $DAYS DAY)
 GROUP BY win ORDER BY win;"
RES_BRK=$(dbq "$Q_BRK")
if [ -z "$RES_BRK" ]; then echo "    양쪽 창 모두 발동 0건 (0=0 동수 → 충족)"; else echo "$RES_BRK" | sed 's/^/    /'; fi

# ---------- ③ 사이징 계약: 수량 ≤ riskBudget ÷ (가격×손절폭%) ----------
echo ""
echo "[③] ATR 사이징 계약 위반(수량이 리스크 균등 상한 초과) — 0건이어야 충족"
# response_msg 예: "strategy=SWING atr=1234 stopPct=7.5 targetPct=... riskBudgetKrw=50000"
# '-'(결측 폴백 = 현행 산식) 행은 계약 검증 대상 아님 — 제외.
Q_VIOL="SELECT COUNT(*)
 FROM $DB.trading_audit_log
 WHERE triggered_by='ATR_SIZING'
   AND response_msg LIKE '%stopPct=%' AND response_msg NOT LIKE '%stopPct=-%'
   AND response_msg NOT LIKE '%riskBudgetKrw=-%'
   AND quantity > FLOOR(
     CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(response_msg,'riskBudgetKrw=',-1),' ',1) AS DECIMAL(15,2))
     / (price * CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(response_msg,'stopPct=',-1),' ',1) AS DECIMAL(10,4)) / 100)
   ) + 1;"   # +1 = 반올림 여유
VIOL=$(dbq "$Q_VIOL")
TOTAL_SNAP=$(dbq "SELECT COUNT(*) FROM $DB.trading_audit_log WHERE triggered_by='ATR_SIZING';")
echo "    적용 스냅샷 ${TOTAL_SNAP}건 중 계약 위반 ${VIOL}건"
echo ""
echo "    최근 적용 스냅샷 10건(수동 eyeball — 수량 급증 없어야 정상):"
dbq "SELECT created_at, stock_code, quantity, ROUND(price,0), LEFT(response_msg,80)
     FROM $DB.trading_audit_log WHERE triggered_by='ATR_SIZING'
     ORDER BY created_at DESC LIMIT 10;" | sed 's/^/    /'

# ---------- 종합 ----------
echo ""
echo "==================================================================="
echo " 종합(기계 판독 — 최종 결정은 사람):"
echo "   ① 동수익: 위 pnl_sum 비교 (ON ≥ BASE ?)"
echo "   ② 브레이커: ON trips ≤ BASE trips ?"
echo "   ③ 계약 위반: ${VIOL}건 (0건이어야 충족)"
echo "   경과일: ${DAYS}일 (14일 이상이어야 판정 유효)"
echo ""
echo " 셋 다 충족 + 14일↑ → REAL 확장 '검토' 단계로(그때도 단계적)."
echo " 하나라도 미달/판정불가 → VIRTUAL 유지, 2주 뒤 재실행."
echo "==================================================================="
