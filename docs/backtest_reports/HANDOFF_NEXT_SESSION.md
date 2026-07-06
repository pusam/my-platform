# 시그널 검증 백테스트 — 다음 세션 인수인계 (2026-07-06 밤 중단)

> **작업 원문 요지**: 차트 백테스트 인프라(app/backtest/*)를 확장해 종합점수 4카테고리 +
> 가설 2건(수급 연속일 vs 금액 · ATR vs 고정 청산)의 예측력을 검증, P1-6 가중치 재조정
> 근거를 즉시 확보. **전부 측정 전용 — 라이브 산식/시세경로 무변경, Phase 4(RVOL 표시)만
> 유일한 라이브 코드.** Phase별 독립 커밋 + 테스트 green + 마지막 push.

## 진행 상태

| Phase | 상태 | 커밋 |
|---|---|---|
| **1. 종합점수 백테스트 엔진** | ✅ **완료** (코드+테스트+실측 리포트) | `25b357b` |
| **2. 수급 연속일 vs 금액** | 🔶 코드+테스트 완료, **실측 리포트만 남음** | `4fc0811` |
| **3. ATR vs 고정 -3/+5** | 🔶 코드+테스트 완료, **실측 리포트만 남음** | `880d77e` |
| **4. RVOL 지표(Java, 표시 전용)** | ❌ 미착수 | — |
| **5. 종합 리포트 md** | ❌ 미착수 (1~3 결과 필요) | — |

pytest 58 green (`python-backend`) · `./gradlew test` green (admin export 추가분 포함).

## Phase 1 실측 결과 요약 (`composite_backtest_2026-07-06.json`)

139종목(SectorStockConfig 스냅샷) × 2026-01-02~06-30 = **16,680 신호**, 전부 point-in-time:

- **수급 역상관 독립 재확인**: 강세(≥15) hitRate **29.67%** < 약세 **31.49%**,
  avgNet **-0.30%** vs **-0.07%**, avgAlpha -1.09. → P1-6 캡(10) 방어 타당성 보강.
  흥미: 5-9 밴드가 최고(hitRate 33.6%, avgNet +0.20) — "적당한 수급"이 극단보다 낫다.
- **기술 약한 양의 변별**: 강세(≥13) avgNet **+0.47%** vs 약세 **-0.23%**, hitRate 33.5% vs
  30.8%. 단 Spearman ≈ 0.02(단조 아님) — 임계 게이트로는 유효, 연속 점수로는 무의미.
- 실적·섹터테마 = **미측정**(가격으로 재현 불가, `measured:false`) — 운영 스냅샷 CSV 필요하나
  **RecommendationSnapshot 보존 7일**이라 커버리지 한계(아래 export 참고).

## 환경 함정 (이 세션에서 확인한 것 — 다른 PC에서 그대로 만남)

1. **pykrx 투자자 거래대금(`get_market_trading_value_by_date`)도 전구간 0행** — 지수와 같은
   KRX 포맷 깨짐 계열(신규 발견). 수급 데이터는 **네이버 frgn 크롤**(investor_flows.py,
   수량×종가 근사)로 우회 완료. 종목 OHLCV 는 정상.
2. KOSPI BM/regime = Java KIS 우선 → **Yahoo ^KS11 폴백**(로컬은 Yahoo 로 동작 확인).
3. 로컬 Python 없으면: `winget install -e --id Python.Python.3.12 --scope user` 후
   ```bash
   cd python-backend
   python -m venv .venv
   .venv/Scripts/python -m pip install -r requirements.txt   # lxml 포함(커밋됨)
   .venv/Scripts/python -m pytest -q                          # 58 green 확인
   ```
4. 네이버 flows 캐시(`.backtest_cache/`, gitignore)는 PC-로컬 — 새 PC 첫 실행 시
   재크롤 ~8분(139종목×~8페이지, 0.25s 딜레이).

## 남은 작업 커맨드 (Phase 2·3 — 코드는 커밋됨, 실행만)

```bash
cd python-backend
# Phase 2 실측 (약 10~15분: OHLCV+flows 크롤+재생 2회)
.venv/Scripts/python -m app.backtest.run_supply_hypothesis --start 20260102 --end 20260630
# → docs/backtest_reports/supply_hypothesis_<date>.json 커밋 (prefix: feat 또는 docs)

# Phase 3 실측 (flows 불필요 — OHLCV만이라 더 빠름)
.venv/Scripts/python -m app.backtest.run_exit_backtest --start 20260102 --end 20260630
# → docs/backtest_reports/exit_backtest_<date>.json 커밋
```

## Phase 4 (RVOL) 설계 메모 — 미착수

- 순수함수: RVOL = 당일 거래대금 ÷ 20일 평균 거래대금. **20일 미만 데이터 → null(§4c)**.
  소스 후보: `StockPriceHistory`(volume 필드 확인 필요 — 거래대금 없으면 종가×거래량).
- 표시: 종합판단 보드(`JudgmentBoardService`/`SectionJudgmentBoard.vue`) **② 참고 계층**
  (재료 배지 옆 "RVOL 3.2x"), unverified·랭킹/산식 미편입.
- 스냅샷: `signal_outcome` 에 `rvol_at_signal` 컬럼 — **V41** 마이그레이션(V37/V39/V40 패턴,
  NULL=미수집), `SignalOutcomeService.record()` 에서 best-effort 채움,
  `FlywayMigrationTest` 에 V41 도달 가드 추가. **`./gradlew test` + `:backend:migrationTest`**
  (⚠ migrationTest 는 Docker 필요 — 이 데스크톱엔 Docker 없음, CI가 검증).

## Phase 5 (종합 리포트) 구성 메모

`docs/backtest_reports/SIGNAL_VALIDATION_2026-07.md`:
Phase 1~3 결과 표 + 카테고리별 권고(기술=게이트 유지 / 수급=캡 유지·연속일 가설 결과 반영 /
실적·섹터=미측정 명시, 스냅샷 축적 후) + 수급 금액vs연속일 결론 + ATR vs 고정 결론
("고정이 이기면 현행 유지") + 한계(생존편향·7일 스냅샷 보존·네이버 근사·자기상관·
운영 signal_outcome 과 모집단 상이) + **권고까지만, 산식 변경은 사람이 별도 세션 결정**.

## 참고 — 운영 데이터 export (선택, 커버리지 확장용)

- 스냅샷 CSV: `GET /api/admin/backtest-export/recommendation-snapshots?from=…&to=…`(ADMIN)
  → `--snapshot-csv` 로 주입 시 실적/섹터 측정 + Phase 3 신호셋이 total>=55 정밀화.
  단 보존 7일이라 최근 1주뿐 — 장기 축적하려면 주기 export 필요(리포트에 한계로 명시).
- 수급 정밀 CSV(`--flows-csv`): 스키마 `date,stock_code,frgn_net_eok,inst_net_eok`
  (InvestorDailyTrade 기반 export 엔드포인트는 미구현 — 필요 시 Phase 2 보강).

## 불변식 리마인더 (작업 지시 원문)

단일 시세경로 · 라이브 산식/임계 무변경 · §4c 결측 위장 금지 · §16-10 URI ·
검증 안 된 신호 산식 미편입 · **미러 함수(technical/supply/PLAN 상수)는 Java 산식의 사본 —
Java 쪽이 바뀌면 미러·테스트도 같이** (측정 코드가 산식을 역으로 구속하면 안 됨).
