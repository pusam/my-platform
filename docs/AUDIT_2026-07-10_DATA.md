# AUDIT 2026-07-10 — 데이터 정합성 (운영 DB READ-ONLY)

> 진단 세션 — SELECT 전용, **코드/DB 무수정**. 수정은 합의 후 별도 세션.
> 코드 감사 2회(봇·산식 / 표시·프롬프트) 후, **저장된 데이터 자체**를 4축(신선도·재계산·분포·cross-table)으로 검증.
> 본 문서는 감사에서 튀어나온 이상 **3+1건의 코드 추적 정밀 진단**이다(축별 실측 수치는 `audit_data.sql` 출력으로 보강 예정).
> 각 건 판정: **코드버그 / 소스死 / 정상(구조적)** 중 하나로 1차 분류.

## 🔎 요약 — P0/P1 없음(실자금 활성 피해 미확인). P2 2 · P3 2.

| # | 심각도 | 판정 | 이상 | 한 줄 |
|---|---|---|---|---|
| 1 | **P2** | **코드버그(복합)** | `bot_trading_position` 298040 SWING VIRTUAL 06-23 매수분 17일 개방 | SWING 매도 루프가 **in-memory 전용** + 강제청산이 **DB 행 미삭제** → 행이 실제 포지션을 과대표시(유령/고아). 확정 원인은 라이브 상태 확인 필요 |
| 2 | **P2** | **코드버그(§4c 위반)** | `rvol_at_signal` 평균 0.59 (RVOL은 1.0 근처여야) | 장중 **부분집계 분자 ÷ 풀데이 분모** 구조편향. 결측엔 null 정직하나 **장중 부분값은 가드 없이 저장** → `accuracy-by-band` 오염 |
| 3 | **P3** | **정상(구조적)** | `stock_price_history` 07-10 3행 | 전용 벌크배치 없음 — 온디맨드/지연 백필이라 **최근일 적고 며칠 뒤 채워짐**(back-loaded). 급감 아님(7일 SQL로 최종 확인) |
| + | **P3** | **정상(의도)** | SWING 행 `original_quantity` 전부 NULL | SwingPosition에 수량 필드 없음(매도는 `portfolio.getQuantity()` 사용). SCALPING만 세팅 — 의도된 분기, 무해 |

---

## #1 — 봇 포지션 298040 SWING VIRTUAL 17일 개방 (P2, 코드버그 복합)

**생명주기(확정)**: `bot_trading_position`은 `status`/`sell_time` 없음 → **청산=하드 DELETE**(`deletePersistedPosition` `AutoTradingBotService:934-941`). **행 존재 = 봇이 열린 포지션으로 간주.**

**핵심 구조 결함 3종**:
1. **강제청산이 DB 행을 안 지운다** — `executeRegularSessionLiquidation`(2500-2503)·`executeNxtLiquidationRetry`(2576-2579)는 **in-memory 맵 clear + `markLiquidatedToday()`만** 하고 **DB 행 미삭제**. → 장중 청산돼도 DB 행 잔존, **재시작 시 `restorePositionsFromDb` 재적재 → 매도 루프의 `portfolio==null` 분기(3123-3124)에서야 삭제**. **즉 `bot_trading_position` 행은 "현재 열린 포지션"의 신뢰 가능한 뷰가 아니다**(이미 청산된 유령 행 가능).
2. **매도 루프가 in-memory 전용** — `executeSwingSellLogicInternal`(3108-3226)은 DB가 아니라 **`swingPositions` 맵**을 순회(3120). 맵은 신규 매수(2976) 또는 restart 시 restore 로만 채워짐. `restorePositionsFromDb`는 **현재 모드 행만 적재**(`findByTradingMode(currentMode)` 711). → **봇이 현재 REAL 모드/정지면 VIRTUAL 298040 행은 메모리에 없어 어떤 매도/TIME_CUT 도 평가 안 함** → 무기한 고아.
3. **null 시세가 TIME_CUT보다 먼저 `continue`(순서 버그)** — `getStockPrice` null이면 3128-3129에서 `continue`, **day-5 TIME_CUT(3163)에 도달 못 함**. → **정지/상폐 종목은 메모리·포트폴리오에 있어도 영원히 시간청산 안 됨**. ⚠ 이 순서버그는 **REAL 모드에도 동일 적용** — 상폐 종목이 REAL 포지션이면 실자금이 묶일 수 있음(현재 REAL 비활성으로 완화).

**왜 17일?** TIME_CUT(holdDays≥5, 3163)이 있으므로 **평가만 됐다면 5일차에 청산**됐을 것. 17일 생존 ⇒ 3123/3204에 **한 번도 도달 안 함** = ① 청산됐는데 DB만 잔존(유령) 또는 ② 모드 불일치로 미적재 또는 ③ null 시세 단락. **셋 다 코드결함**.

**red herring 배제**: `original_quantity=NULL`은 원인 아님 — SWING 매도는 `portfolio.getQuantity()`(3171) 사용, `original_quantity` 미참조(NPE/skip 불가).
**PositionDropMonitor 무관**: REAL 전용·알림 전용(`checkDrops` `isRealTradingConfigured` 게이트, 매도 안 함).

**확정용 라이브 체크(원인 ①/②/③ 판별)**:
```bash
# 현재 봇 모드/활성 (② 판별) + 298040 실시세 null 여부 (③ 판별)
docker compose exec -T mariadb sh -c 'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot -t myplatform' <<'SQL'
SELECT * FROM bot_config;
SELECT strategy, stock_code, trading_mode, buy_time, buy_price, high_price FROM bot_trading_position WHERE stock_code='298040';
SQL
curl -s localhost:8080/api/stock/298040/quick | head -c 400   # 시세 null/정상 확인 (③)
```
- `bot_config` 모드가 REAL → **②(모드 불일치 고아)** 유력. VIRTUAL인데 행 잔존 → **①(강제청산 미삭제 유령)** 또는 ③.
- 298040 시세 null/에러 → **③(정지/상폐 → null 단락)** 확정.

**수정안(보고 후)**: ⓐ 강제청산 경로에 `deletePersistedPosition` 추가(유령 행 근절, 최우선) · ⓑ null-시세 `continue`를 **TIME_CUT 판정 뒤로 이동**(정지종목도 시간청산 — REAL 안전도 개선) · ⓒ restart reconciliation처럼 **모드 불일치 고아 행 정리/경고**(§4d 정합). ⓐⓑ는 순수 로컬·봇 클럭 주입이라 재현 테스트 가능.

---

## #2 — rvol_at_signal 평균 0.59 (P2, 코드버그 §4c 위반)

**메커니즘(확정)**: RVOL = 당일거래대금 / 20일평균. **분자=장중 부분집계**(`resolveTradingValue` = KIS 누적거래대금 or 현재가×거래량, 개장 후 누적 — `JudgmentBoardService:383-391`, `RvolService:45-58`), **분모=`stock_price_history` 20일 풀데이 평균(오늘 제외)**(`RvolService:90-119`). `record()`가 대부분 **장중** 발화(추천 11:30/14:00/17:00 `RecommendationService:260-262` · Composite 25분 워밍 · InvestorSurge 10분) → 분자 ≈ 세션경과율 ≈ 0.6. **관측 0.59와 일치**.

**§4c 판정**: RvolService는 **결측엔 null 정직**(캐시미스·<20일·비양수)하나 **데이터가 있는 장중 부분값은 가드 없이 저장** → "부분 데이터를 풀데이 RVOL로 위장". 코드 Javadoc(`RvolService:22-24`)도 편향 인지("장중엔 당일 누적이 하루치보다 작게… 참고 표시로 충분") — **배지 표시엔 용인이나, `rvol_at_signal` 스냅샷(검증 통계)에 유입되면 §4c 위반** (체결강도 null-not-100과 동형). `accuracy-by-band`의 rvol 구간 통계가 **일괄 하향편향으로 오염**.

**수정안(보고 후, 권장순)**: **ⓐ 세션 미완이면 `rvol_at_signal`=null**(최소·§4c 정합·기존 null 패턴 재사용, 배지는 유지 — 스냅샷 경로만 null). 표본↓지만 **작은 무편향 > 큰 편향**. · ⓑ 종가 후(20:05/19:30) 풀데이 분자로 **백필**(최고품질·비용↑). · ⓒ 시간비례 스케일링은 **위장이라 지양**(장중 볼륨 U자 편중, 선형보정 자체가 오차).

---

## #3 — stock_price_history 07-10 3행 (P3, 정상 — 구조적 지연 백필)

**메커니즘(확정)**: **전용 벌크 일배치 없음.** 저장은 `savePriceHistoryToDb`(`StockAnalysisService:1006`) 하나, 세 트리거 모두 **개별 종목**: ① `analyzeTechnical`(651) — DB가 **4일 이내 fresh면 KIS 재조회 안 함**(today 행 미삽입), 노후/부족만 fetch · ② `collectPriceHistory`(990) ← `RecommendationService:1959`(calculate() 중 **부족 종목만** 비동기 백필)·`QuantTaService:827` · ③ 저장 시 **기존 날짜 스킵**(existsByStockCodeAndTradeDate 1032).

**판정**: `trade_date`별 행수는 "그날 일괄 삽입"이 아니라 **종목이 개별적으로 노후·리프레시될 때 과거 60일이 뒤늦게 백필** → **최근일수록 적고 며칠 뒤 채워지는 back-loaded 패턴**. **"07-10 3행"은 정상**(전용 배치 전/급감 아님). DATA_HEALTH_CHECK §6의 "rows_latest 수백~천" 기대치가 이 메커니즘과 불일치(과대 기대 — 문서 정정 대상).

**최종 확인 SQL**(7일 추이 — 최근일만 적으면 정상, 7일 전부 한자리수면 백필경로 死):
```sql
SELECT trade_date, COUNT(*) rows, COUNT(DISTINCT stock_code) stocks, MAX(created_at) last_ingest
  FROM stock_price_history WHERE trade_date >= CURDATE()-INTERVAL 9 DAY
  GROUP BY trade_date ORDER BY trade_date DESC;
```
⚠ 부수 관찰(별건 후속): analyzeTechnical가 today 행을 **장중 삽입**하면 그 종가는 **장중 스냅샷**인데 existsByStockCodeAndTradeDate가 **종가 후 재삽입을 막아** 장중값이 굳을 수 있음(§4c 잠재, 낮은 우선순위).

---

## 보너스 — SWING original_quantity 전부 NULL (P3, 정상 — 의도된 분기)

`persistSwingPosition`(894-916)엔 `setOriginalQuantity` 없음(`SwingPosition`에 수량 필드 자체 없음). SCALPING만 `persistScalpingPosition:888`에서 세팅(재시작 복원용 721). SWING 매도는 `portfolio.getQuantity()` 사용이라 **NULL이 매도를 막지 않음**(#1의 원인 아님). **스키마 의도, 무해.**

---

## 권장 수정 그룹 (보고 후 별도 세션)
1. **#1 봇 행 정합(P2)**: ⓐ 강제청산에 DB delete 추가 · ⓑ null-시세 continue를 TIME_CUT 뒤로 · ⓒ 모드 불일치 고아 경고/정리. (재현 테스트 有, 봇 Clock 주입)
2. **#2 rvol §4c(P2)**: 세션 미완 시 `rvol_at_signal`=null(스냅샷 경로 한정, 배지 무변경). 회귀 테스트 有.
3. **문서**: DATA_HEALTH_CHECK §6 기대치를 "on-demand 백필 = 최근일 back-loaded"로 정정.
4. **#3/보너스**: 무수정(정상).

## 한계
- 축1(신선도)·축3(분포)·축4(cross-table) 전수 수치는 `audit_data.sql` 출력 붙으면 본 문서에 표로 보강. 현재는 그 출력에서 드러난 4개 이상의 코드 추적 진단.
- #1의 ①/②/③ 확정은 라이브 상태(봇 모드·298040 시세) 확인 필요 — 위 체크 커맨드 결과로 특정.
