# HANDOFF — 수동 매매 저널 (ManualTradeJournal)

> 2026-07-07 세션. 토큰 소진으로 Phase 1 첫 커밋 후 인계. 이 문서 + `git log` 만으로 이어갈 것.

## 목표
봇 매매는 감사로그·주간리포트로 추적되나 **사용자 본인 수동 매매는 기록 없음**. 매수 순간 신호를
스냅샷하고 3거래일 후 자동 평가 → "내 매매 적중률"을 `signal_outcome` 과 **같은 잣대**로 측정.

## 불변식 (반드시 준수)
- 단일 시세경로(`StockPriceService`)·산식/임계 무변경·§4c(결측=null, 위장 금지).
- **봇 안전장치(§4d)·`VirtualTradeHistory`·주문 경로 무접촉 — read 만, write 금지.** 이 저널은 완전 분리.
- Flyway: **V42 가 직전 최신 → V43 사용함**(이 세션). 다음 신규 필요 시 V44.

---

## [완료] Phase 1-1 — 커밋 `58b968d`
- `V43__create_manual_trade_journal.sql` — CREATE TABLE(신규 자립, baseline-14 무관).
- `entity/ManualTradeJournal.java` — 매수(buyAt/buyPrice/quantity/memo) + 스냅샷 12필드
  (totalScore·earnings·supplyDemand·technical·sectorMomentum·rsi·catalystType·catalystDirection·
  rvol·regime·atrStopPct·fiveDayReturn, 전부 null 허용 §4c) + 매도(sellAt/sellPrice/realizedPct)
  + 평가(pctChange3d/alpha3d/hit/evaluatedAt) + createdAt.
- `repository/ManualTradeJournalRepository.java` — `findByUsernameOrderByBuyAtDesc`,
  `findByIdAndUsername`(소유검증), `findByUsernameAndStockCodeOrderByBuyAtDesc`(종목상세 마커),
  `findPendingEvaluation(beforeAt)`(평가 대기). **compileJava green.**
- v1 스코프: **매도 전량 가정**(부분매도 미지원 — 주석 명시).

## [완료] Phase 1-2 — 커밋 `a2b0e23` (Phase 1 전체 완료)
- `ManualTradeJournalService`(recordBuy 스냅샷 자동·recordSell 전량·list/get 소유검증,
  순수함수 assembleSnapshot/fiveDayReturn/realizedPct + 테스트 4) + `ManualTradeJournalController`
  (/api/manual-journal POST·PUT /{id}/close·GET /·GET /{id}). 타겟 테스트 green.
- RSI 는 diagnose() 사용으로 결정(heavy 하나 사용자 단발 액션 — 허용). ATR 은 AtrExitRule.judge 재사용.
- 미실행: `:backend:migrationTest`(로컬 Docker 미기동 — **CI 가 V43 검증**), 전체 test 는 CI 게이트.
- **다음 세션 시작점 = Phase 2** (아래 원문 요지 그대로). STOCK_AZ_FULL.md 갱신도 미완(Phase 3 후 일괄 권장).

## [참고·원계획] Phase 1-2 상세 (완료됨 — 소스 위치 기록 보존용)
1. **`ManualTradeJournalService`** 신규. 스냅샷 수집은 **순수 조립 함수 분리 + 테스트**, 각 소스
   best-effort(실패=해당 필드 null, 기록은 항상 성공). 소스별 재사용 위치(중복 구현 금지):
   - 종합점수·4카테고리: `RecommendationSnapshotRepository.findLatestByStockCode(code)` →
     `RecommendationSnapshot`(getTotalScore/getEarnings/getSupplyDemand/getTechnical/getSectorMomentum).
     스냅샷 없으면 전부 null.
   - RSI + (5일 등락률용 일봉): RSI 는 `StockAnalysisService.diagnose(code).getTechnicalAnalysis().getRsi14()`
     — ⚠ **diagnose() 는 heavy**(재무+수급+기술 조회). 기록은 사용자 단발 액션이라 허용 가능하나,
     더 가벼운 경로 원하면 기술지표 리포/서비스 직접 사용 검토. **판단 필요**.
   - 5일 등락률: `StockPriceHistoryRepository.findByStockCodeOrderByTradeDateDesc(code, PageRequest.of(0,6))`
     → `(closes[0]-closes[5])/closes[5]*100`. RecommendationService ~L1919 에 동일 패턴(헬퍼 아님, 소량 재현).
   - 재료: `StockCatalystRepository.findByStockCodeAndCatalystDate(code, LocalDate.now())` **read-only**
     (⚠ classify 호출 금지 — §4b quota). NONE/없음이면 catalystType/direction null.
   - RVOL: `RvolService.getRvolQuiet(code)`(cache-only 단일 시세경로, null=§4c).
   - regime: `MarketRegimeClient.getCurrentRegimeQuiet()` — `ObjectProvider<MarketRegimeClient>` best-effort.
   - ATR 손절참고: `AtrCalculator.atr14(history40)` + `AtrExitRule.judge(buyPrice, atr).stopPct()`
     (StockConclusionService.computeAtrLevelsQuiet 와 동일 — 그건 private, 로직 재현 or util 승격).
   - 현재가 프리필/기준가: `StockPriceService.getStockPrice(code)`(단일 경로).
2. **`ManualTradeJournalController`** `/api/manual-journal`:
   - `POST /` 매수(body: stockCode, stockName, buyPrice, quantity?, memo?) → 스냅샷 자동 채움 후 저장.
   - `PUT /{id}/close` 매도(body: sellAt?, sellPrice) → realizedPct=(sell-buy)/buy*100 확정.
   - `GET /` 리스트, `GET /{id}` 단건. **소유 검증** = `Authentication` principal username
     (`WatchlistController.getUsername(auth)` 패턴 복사) + `findByIdAndUsername`.
3. 커밋 단위: 서비스+조립테스트 → 컨트롤러. 각각 별도 커밋.

## [미착수] Phase 2 — 자동 평가 + stats API
- 매수 3거래일 후 pct/alpha: **`SignalOutcomeService` 의 bm/alpha 계산 재사용**(벤치마크=KIS 지수
  `getIndexPrice("0001")`, alpha=종목수익-지수수익). **hit = alpha_3d≥0 AND pct_change_3d>0**
  (signal_outcome 동일, 폴백 pct≥3%). pending/완료 구분(§4c, evaluatedAt null=대기).
- 배치: 기존 19:30 시그널 평가 패턴 재사용 — 새 @Scheduled 메서드가 `findPendingEvaluation(3거래일前)`
  순회. ⚠ **3거래일 계산**은 SignalOutcomeService 가 이미 거래일 로직 보유 → 재사용(달력일 아님).
- 매도 시 realizedPct 는 close API 에서 이미 확정(평가 배치와 별개).
- `GET /api/manual-journal/stats` — 총건수·3일 적중률·평균 alpha·실현 승률(봇/신호와 나란히 비교 형태).
  스냅샷 조건 breakdown(RSI≥70 vs 미만, 재료 유무 등)은 **구조만** + **n<10 = insufficientSample:true(§4c)**.

## [미착수] Phase 3 — 프론트 (새 라우트 금지)
- 진입점: 종목상세 `BuyChecklistModal` 하단 "📔 매수 기록" 버튼 + 결론카드 근처 1개.
  폼 = 가격(현재가 프리필)·수량·메모만(스냅샷은 서버 자동, 폼 미노출).
- 섹터 집중 경고: 폼에 "동일 섹터 보유 N종목"(열린 저널+봇 포지션, `SectorStockConfig` 매핑,
  매핑 밖=미표시 §4c). **경고만, 차단 없음.**
- 조회: `PaperTradingPage`(매매 탭)에 "수동 매매" 서브 섹션 — 리스트(스냅샷 요약 칩: 점수·RSI·재료)
  + stats 카드.
- `SignalHistorySection`(종목상세)에 내 매수/매도 마커 병기(`findByUsernameAndStockCode` 활용).

## 이 세션에서 발견한 함정/결정
- **Flyway baseline-14 하이브리드**(STOCK_AZ_FULL §13 부근): V15+ 만 운영 실행. **신규 테이블 CREATE 는
  자립**이라 안전. `FlywayMigrationTest`(@Tag("migration"), Testcontainers mariadb:11.2)가 V15→V43 실행 —
  **로컬 Docker 없으면 CI 가 검증**(`:backend:migrationTest`, 기본 test 는 migration 태그 제외).
- diagnose() heavy 문제(위 Phase1-2 참고) — RSI 소스 선택은 다음 세션 판단.
- 스냅샷 조립 = 순수함수로 분리해야 테스트 가능(각 소스는 이미 조회된 값/DTO 를 인자로 받게 설계 →
  서비스는 조회만, 조립은 pure). ATR util 은 private 재사용 곤란 시 로직 재현 or 승격.
- 테스트: `./gradlew test -PskipFrontend` + `:backend:migrationTest`(Docker) + `cd frontend && npm test && npm run build`.
- 완료 후 `STOCK_AZ_FULL.md` 갱신(엔티티 목록·컨트롤러·§11 프론트) 필수.
