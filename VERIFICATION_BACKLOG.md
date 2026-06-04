# 검증 / 개선 백로그

각 티켓은 그대로 Claude Code에 던질 수 있게 **문제 / 합격 기준 / 테스트**로 구성.
위에서부터 순서대로 처리 권장. P0가 실제 버그, P2는 정합성/개선.

---

## P0-1. 가격 outlier 가드 맹점 (실제 버그)
- **문제**: `warnIfPriceOutlier`가 "현재가가 당일 [저가~고가]±10%"로만 보는데, 현재가·고가·저가가 **일괄 ×10**되면 현재가가 여전히 범위 안이라 가드가 발화하지 않는다. 당일 범위 조건은 일괄 스케일링에 무용지물.
- **합격 기준**:
  1. 현재가·고저가가 일괄 ×10된 케이스 → 가드 발화(ERROR 로깅).
  2. 정상 종목(소폭 등락) → **절대 미발화**.
  3. 전일 종가 0/null → 예외 없이 skip.
  4. 가격은 여전히 **미보정** (로깅만).
- **구현 가이드**: "현재가 ≥ 전일 종가 × N(예: 5)" OR 조건을 기존 범위 조건에 추가.
- **테스트**: 위 4케이스 단위테스트 + 경계값(×4.9 미발화 / ×5.1 발화) + 기존 `StockDetailServiceTest` 회귀.

## P0-2. ×10 근본원인 조사 (수정 아님, 진단)
- **문제**: 코드엔 ×10 연산이 없는데 일부 종목만 ×10. KIS `FHKST01010100` + `UN`(KRX+NXT 통합시세) 사용 중. NXT/이중상장 종목의 통합시세 필드 규약 차이 의심.
- **합격 기준**: ×10 발생 종목 목록을 뽑고, 그 종목들이 NXT/이중상장/특정 구간에 몰리는지 분류. "응답 자체가 ×10" vs "필드 매핑 오류" 중 어느 쪽인지 KIS raw 로그로 확정.
- **산출물**: 진단 스크립트/테스트 + 분류 결과 요약. (근본 보정은 결과 확인 후 별도 티켓)
- **✅ 결론 (2026-06-04, 운영 90일 실측)**: `CURRENT_FIELD_OUTLIER 0건` (현재가가 자기 당일 밴드 밖인 행이
  전무) → **×10 현재가 오염 미발생 확정**. MIN 앵커 스캔의 대량 매칭은 전부 false positive(저측 글리치 앵커
  + 동결/스테일 피드 + 실제 5×+ 랠리)였음. 부수 관찰(동결 피드·저측 글리치·손상 등락률 900%)은 ×10과 무관한
  별개 현상 → 필요 시 별도 티켓. 상세: `docs/PRICE_X10_DIAGNOSIS_P0-2.md` §6.

---

## P1-3. 시세 단일 경로 회귀 가드
- **문제**: 화면 간 가격 불일치는 시세 경로가 갈라질 때 생긴다. 지금은 공용 `getStockPrice()`로 통일됐으나 회귀 방지 장치가 필요.
- **합격 기준**: `getQuick`/`getHeavy`/목록이 모두 `StockPriceService.getStockPrice()`를 경유함을 보장하는 테스트(별도 시세 fetch 추가 시 실패).
- **✅ 완료 (2026-06-04)**: `StockDetailServiceTest` 에 `SinglePriceSourceGuard` 추가. getQuick 표시가격 =
  stockPriceService sentinel 그대로 + `verify(kisService, never()).getStockPrice` (병렬 KIS 표시가격 fetch
  추가 시 실패), getHeavy 도 `verify(stockPriceService, atLeastOnce()).getStockPrice` 경유 확인. 핫패스 무변경.

## P1-4. 시그널 hit 산식 테스트
- **문제**: `SignalOutcomeService`의 hit 판정 검증 부족.
- **합격 기준**: alpha≥0 & pct>0 → hit / 둘 중 하나라도 미달 → miss / alpha null → 폴백 pct≥3% / pct 정확히 0·경계값 처리 확인.
- **✅ 완료 (2026-06-04)**: hit 공식을 `SignalOutcomeService.isHit(alpha, pct)` 순수 메서드로 분리(동작 보존,
  인라인 대체) + `SignalOutcomeHitTest` (alpha=0·pct=0·pct=2.99/3.00 경계 등 9케이스).

## P1-5. 추천 점수 경계·coverage 테스트
- **합격 기준**: validCount<3 → 미채택 / 임계 40·55·75 경계 분류 정확 / total≥75 & value≥12 → +2 보너스 적용 / MarketRegime 승수(BULL·BEAR) 반영 확인.
- **✅ 완료 (2026-06-04)**: `RecommendationService` 의 순수 산식을 package-private 로 분리(동작 보존):
  `normalizeScore`(경계), `validCount`(≥3 컷 분모), `strongValueBonus`(+2), `applyRegimeWeights`(BULL/BEAR
  승수 + clamp), `MarketRegime` enum. `RecommendationScoreTest` — raw32/44/60→40/55/75, validCount cap,
  보너스 75&12→77(상한 100), BULL 섹터×1.2/BEAR 실적×1.2 clamp 등. 기존 `toDto`/regime weighting 은 위
  헬퍼로 위임(분기·태그 동작 동일).

---

## P2-6. 봇 트랙 수 문서·코드 정합
- **문제**: 문서엔 "3봇" "4트랙"이 혼재하고 청산봇(`executeClosing*`)은 주석처리(비활성). 셈법 3종이 서로 안 맞음.
- **합격 기준**: 실제 active 트랙을 코드 기준으로 확정해 주석/문서에 명문화. 청산봇 재활성 여부는 결정 사항으로 분리 제안.

## P2-7. isRegularSession 종료 시각
- **문제**: `MarketCalendarService.isRegularSession`이 09:00~**15:40**인데 KRX 정규장은 09:00~15:30. 종가단일가 버퍼 의도일 수 있음.
- **합격 기준**: 의도 확인 후 코드 주석 + 경계 테스트(15:30·15:31·15:40·15:41) 추가.

## P2-8. MarketCalendar 음력 공휴일
- **문제**: 고정 공휴일만 처리, 음력(설/추석 등) 누락 가능.
- **합격 기준**: 음력 공휴일 반영 + 해당 일자 isRegularSession=false 테스트.

## P2-9. 시간대 경계 동작 특성화 테스트
- **문제**: NXT(08~20) vs KRX(09~15:30) 분리로 경계 구간(08~09, 15:30~20:00)에서 "화면 during인데 봇/섹터 데이터 비어있음" 같은 동작이 구조적으로 발생.
- **합격 기준**: 경계 시각별 각 모듈 동작을 characterization 테스트로 고정 → 의도된 동작임을 문서·테스트로 못박기.

## P2-10. StockDetailDashboard 분리 (개선)
- **문제**: 4,707줄 단일 컴포넌트.
- **합격 기준**: 동작 보존(스냅샷/행동 테스트 먼저) 후 탭/위젯 단위 분리. 동작 변화 0.

## P2-11. 스테일/글리치 피드 가드 (진단·로깅, 데이터 품질)
- **배경**: P0-2 운영 90일 실측에서 ×10 현재가 오염은 0건이었으나, 그 과정에서 ×10 과 무관한
  데이터 품질 이슈가 드러남 (상세: `docs/PRICE_X10_DIAGNOSIS_P0-2.md` §6).
- **문제 (3종, 모두 ×10 가드가 못 잡음)**:
  1. **동결/스테일 피드** — 거래정지/상폐/스테일 종목이 같은 현재가로 수주간 3분마다 반복 적재
     (예: 001230 이 11,400 에 2026-04-24~05-28 고정). 화면엔 "정상 시세"처럼 보임.
  2. **저측 글리치** — 단발성 ×0.2 수준(정상가의 ~1/5) 행. `MIN()` 기반 분석을 오염시켜 false positive 유발.
  3. **손상 등락률 필드** — `prdy_ctrt` 가 일일 변동제한(±30%)을 황당하게 초과 (예: 011930=900.00%).
     ※ P0-1 그물 2가 이미 ±31% 초과를 ERROR 로깅하나, 동결/저측 글리치는 미감지.
- **합격 기준**:
  1. **동결 감지** — 직전 N회(예: 동일 currentPrice 가 X분 이상/Y틱 연속) 변화 0 이면 stale 의심 로깅
     (정규장 한정; 휴장/장외 동결은 정상이므로 제외). `StockStatusService` 의 거래정지/상폐 목록과 교차해
     "정지종목 동결=정상 / 활성종목 동결=이상" 구분.
  2. **저측 글리치 감지** — DB 앵커(직전 정상가) 대비 ≤0.2배 행을 ERROR 로깅 (P0-1 DB앵커 그물이 이미
     ≤0.2 조건 보유 → **신규 코드 불필요, 재현 테스트로 동작만 고정**).
  3. **손상 등락률** — P0-1 그물 2 회귀 테스트에 900% 같은 극단값 케이스 추가.
- **불변식 준수**: P0-1 과 동일하게 **로깅만, 가격 미보정**. 시세 단일 경로(`getStockPrice`) 변경 금지.
- **테스트**: 동결 N틱 경계(미발화/발화), 정지종목 동결=미발화, 저측 ×0.2 발화, 등락률 900% 발화.
- **비고**: 진단·관측 목적. 실제 차단/대체값 주입은 결과 확인 후 별도 티켓.
- **✅ 구현 완료 (2026-06-04)** — 로깅/관측 전용, 시세 핫패스(`getStockPrice`/`warnIfPriceOutlier`) 무변경:
  - `util/StaleFeedDetector` — 꼬리 동결 길이 계산(순수) + `StaleFeedDetectorTest`.
  - `PriceScalingDiagnosticService.scanStaleFeeds` — 정규장 동결 감지 + `StockStatusService` 활성/정지 교차
    (`ACTIVE_FROZEN`=이상 / `SUSPENDED_FROZEN`=정상) + 손상 등락률(±31% 초과) 보고. 정규장 한정.
  - `GET /api/diagnostics/stale-feeds?tickThreshold=20` 엔드포인트(읽기 전용).
  - 저측 글리치(×0.2)·손상 등락률(900%)은 P0-1 기존 그물이 이미 발화 → `StockPriceOutlierGuardTest`
    회귀 케이스로 동작 고정(신규 보정 코드 없음).
  - 전체 `./gradlew test` green.
  - **운영 점검 절차**: `docs/OPS_STALE_FEED_CHECK_P2-11.md` (엔드포인트 호출 → ACTIVE_FROZEN 판정 →
    거래정지 동기화 누락/피드 스테일 분기, 재배포 전 SQL 대체 포함).
