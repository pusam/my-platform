# 추천 신뢰성 감사 (2026-10-01) — 고친 것 · 표본 경계 확인법 · 남은 항목

읽기 전용 감사(코드 3갈래 + 운영 DB·로그 교차 확인)에서 나온 확정 결함 중 6건을 고쳤다. 각 결함은 **고치기 전에 실패하는
재현 테스트**를 먼저 만들고 고쳤다. 나머지는 범위를 넓히지 않고 아래 백로그로 남긴다(영향·근거·제안).

## 1. 고친 것

| # | 결함 | 구분 | 고친 내용 | 회귀 테스트 |
|---|---|---|---|---|
| 1 | 스크리너 3곳(마법의 공식·PEG·성장)이 종목별 최신 행을 `MAX(report_date)` 로 고르며 미래 행(12-31 추정치)을 집었다 — 그 342종목은 전부 PER 이 없어 통째로 탈락(9/30 기준 조건 충족 251종목, 마법의 공식 후보 854 대비 약 29%) | **추천 입력** | 서브쿼리에 `report_date <= CURRENT_DATE` | `StockFinancialDataScreenerQueryTest`(H2, 실제 JPQL) |
| 2 | AI 스윙 상위 3개에 '알고리즘 95% + Gemini 5%' 를 덮어써 100점 동점일 때 1~3위가 99 이하로 내려가 저장 목록(상위 5)에서 밀렸다(9/30 13:00 SWING 은 4~8위 저장, 9/1 이후 SWING 33회 중 8회) | **추천 입력** | `rankForSnapshot` — Gemini 는 코멘트·테마만, 점수·순위는 알고리즘. 순서는 알고리즘 점수 안정 정렬 | `AiStrategySnapshotRankingTest` |
| 3 | 결론 카드·오늘 탭·종합판단의 '실측 적중률'이 경계 이전 표본 + 레거시 평가값(배치 시점 가격)이었다. 결론 카드 '지난 30일'은 승격 중복까지 셌다 | 표시·측정 | `SignalOutcomeService.currentSample` 단일 경로(표본 시작일 이후 · 보드 신호 · 교정 D+3 OK · 종목·날짜당 최초 기록). 표본이 없으면 '검증 중' | `SignalOutcomeCurrentSampleTest` · `JudgmentBoardTrackRecordTest` · `StockConclusionServiceTest` · 프론트 4종 |
| 4 | 종목 '신호 이력'이 무작위 대조군·급등 감지를 그 종목의 추천 성적으로 셌다(90일 3,723행 중 추천 157 · 대조군 35 · 급등 3,510 / 삼성SDI 는 추천 0건인데 대조군이 '적중') | 표시 | 추천 신호만, 교정 D+3, 현재 표본 행만 요약. 평가 제외 행은 사유 표시 | `SignalHistoryServiceTest` · `SignalHistorySection.test.js` |
| 5 | 장중 부팅이 투자자 수급 잠정치를 그날 일별 기록으로 저장(9/30 11:13 잠정 156행 → 15:50 확정 159행), 16:00·18:00 보완은 '행 있음'으로 건너뛰어 잠정치·부분 수집이 영구화될 수 있었다 | **추천 입력** | 부팅 수집은 15:50 전이면 저장 안 함. 보완 수집은 `InvestorDailyConfirmation.isConfirmed`(외국인·기관 둘 다 + 그날 행 전부 15:50 이후 저장)로 판단. 16:00 은 미확정이면 18:00(그날 행 삭제 후 재수집)에 맡김 | `InvestorTradeSchedulerConfirmedCollectionTest` · `InvestorDailyConfirmationTest` · `HolidayPhantomRowGateTest` |
| 6 | (미배포 코드) 게이트 '이전 산식 참고치'의 대조군 비교에 9/1 이전 대조군이 섞였다 — 그 전 대조군은 유니버스가 달라 edge 과대 방향 | 측정·표시 | `pairableControls` — 짝지은 비교는 9/1(`ControlGroupService.CONTROL_SAMPLE_SINCE`) 이후 대조군만 | `SignalOutcomeTrustGateWiringTest` |
| — | 표본 경계를 코드에 10/2 로 박아 두었다 — 같은 날 입력이 또 바뀌어 근거를 잃었다 | 측정·표시 | `SignalSampleBoundary` — 기본 **미정**, `RECOMMENDATION_SAMPLE_SINCE`(날짜) + `RECOMMENDATION_SAMPLE_CONFIRMED`(확정 여부). 미정이면 게이트 현재 판정·화면 성적 모두 '검증 중' | `SignalSampleBoundaryTest` · `ControlRoomTrustGateBoundaryTest` · `ControlRoomKpis.test.js` |

**추천 입력이 바뀌는 것(1·2·5)** — 배포일이 표본 경계다:
- 1 → AI 스윙·가치 후보 풀, 모닝브리핑 '마법의 공식 TOP 3', PEG 스크리너. AI 스냅샷을 통해 종합추천의 AI 시드(후보 풀 확장)와
  Gemini 테마 가산(섹터 축)이 붙는 종목이 바뀐다.
- 2 → 같은 경로(AI 스냅샷 목록·순서 → 종합추천 AI 시드·테마 가산).
- 5 → 장중 재시작이 있던 날의 종합추천 수급 축(그날 11:30·14:00 이 잠정치 대신 직전 거래일 확정치를 읽는다).

**표시·측정만 바뀌는 것(3·4·6·경계)** — 점수·순위·BUY·봇은 그대로다. 단 화면 성적 숫자는 크게 달라진다(대부분 '검증 중').

## 2. 표본 경계 — 날짜를 고정하지 않고 확인해서 정한다

경계는 **배포 시각이 아니라** 마지막 입력 수정이 재무 수집·AI 스냅샷·추천 캐시·추천 스냅샷까지 반영된 뒤의 **첫 온전한
거래일 D** 다. 아래를 전부 만족해야 D, 하나라도 아니면 다음 거래일로 다시 본다.

1. **재무 수집**: 배포 후 첫 08:30 수집이 D 에 끝났다 — 로그 `원버튼 전체 데이터 수집 완료`, `1/2 기본 재무 데이터 완료 - 성공 N,
   실패 0` / 아래 ①의 `per_basis` 분포 · 12월 결산이 아닌데 CTRL 인 행 0.
2. **AI 스냅샷**: D 11:30(그날 첫 추천 스냅샷) 시점에 각 전략의 최신 스냅샷이 전부 ①의 수집 완료 **뒤**에 만들어졌다(아래 ②).
   ⚠ 로테이션이 SWING 10·13·16·19 / TURNAROUND 11·14·17 / **VALUE 12·15·18** / SCALPING 30분마다라, **저녁에 배포하면 다음 날
   11:30 은 전날 만든 VALUE(옛 입력)를 읽는다** — 그래서 저녁 배포의 첫 온전한 날은 보통 **배포 후 둘째 거래일**이다.
   표본은 종목·날짜당 최초 기록(대부분 11:30)이라 11:30 입력이 그날 표본을 대표한다. (2026-10-01 오전 보고에서 "배포 다음
   거래일"이라고 한 것은 이 시차를 빠뜨렸다.)
3. **추천 스냅샷·신호**: D 의 11:30·14:00·17:00·20:05 스냅샷이 있고 추천·대조군 신호가 기록됐다(③).
4. **재시작 없음**: D 08:30~15:50 사이 backend 재시작이 없다. 이번 수정(5)으로 수급 잠정치는 더 저장되지 않지만, 재시작은 캐시와
   AI 스냅샷 시점을 바꾸므로 보수적으로 유지한다. 확인: 배포 시각은 GitHub Actions 배포 run, 컨테이너는
   `docker inspect -f '{{.State.StartedAt}}' <backend>`(UTC). ⚠ 재생성하면 이전 컨테이너 로그는 사라진다.
5. **수급**: D 날짜 행이 15:50 전에 생기지 않았고, 직전 거래일 행은 확정치(외국인·기관 둘 다, 전부 그날 15:50 이후 저장)다(④).

```sql
-- 읽기 전용. @D = 후보일, @P = 직전 거래일
SET @D = DATE('2026-10-05'); SET @P = DATE('2026-10-02');

-- ① 재무 수집 완료 시각(MAX updated_at)과 정의 분포
SELECT per_basis, COUNT(*) n, MIN(updated_at) first_upd, MAX(updated_at) last_upd
  FROM stock_financial_data WHERE report_date = @D AND market_cap IS NOT NULL GROUP BY per_basis;
--    12월 결산이 아닌데(모름 포함) CTRL 인 행 = 0 이어야 한다(V65)
SELECT COUNT(*) non_dec_ctrl
  FROM stock_financial_data f LEFT JOIN dart_company c ON c.stock_code = f.stock_code
 WHERE f.report_date = @D AND f.market_cap IS NOT NULL AND f.per_basis = 'CTRL'
   AND COALESCE(c.fiscal_month, '??') <> '12';

-- ② D 11:30 시점 각 전략의 최신 스냅샷 생성 시각 — 전부 ①의 last_upd 보다 뒤여야 한다
SELECT strategy_type, MAX(created_at) latest_before_1130
  FROM ai_strategy_snapshot WHERE created_at < TIMESTAMP(@D, '11:30:00') GROUP BY strategy_type;

-- ③ 추천 스냅샷과 신호
SELECT snapshot_at, COUNT(*) n FROM recommendation_snapshot
 WHERE snapshot_at >= @D AND snapshot_at < @D + INTERVAL 1 DAY GROUP BY snapshot_at;
SELECT signal_type, COUNT(*) n, MIN(created_at) first_rec FROM signal_outcome
 WHERE signal_date = @D AND signal_type IN ('STRONG_BUY', 'BUY', 'CONTROL_RANDOM') GROUP BY signal_type;

-- ④ 수급 — D 행은 15:50 이후에만, @P 행은 외국인·기관 둘 다 있고 first_ins 가 그날 15:50 이후
SELECT trade_date, investor_type, COUNT(*) n, MIN(created_at) first_ins, MAX(created_at) last_ins
  FROM investor_daily_trade WHERE trade_date IN (@P, @D) GROUP BY trade_date, investor_type;
```

**설정 순서**: D 를 정하면 `.env` 에 `RECOMMENDATION_SAMPLE_SINCE=D` → backend 재생성(장 마감 뒤, 08:30~15:50 피함) = **잠정**.
D 의 20:05 스냅샷 뒤 위 확인이 전부 통과하면 `RECOMMENDATION_SAMPLE_CONFIRMED=true` → 재생성 = **확정**. 이 두 값은 표시·게이트
판정만 바꾸고 추천 입력은 바꾸지 않는다. 날짜 형식이 틀리면 미정으로 본다(부팅 WARN). 판정 통과 조건(30건·고유 10일 이후에도
EVALUABLE 까지, 비용 차감 수익·대조군 우위·낙폭 한도)은 그대로다.

## 3. 남은 항목(백로그) — 이번에 고치지 않았다

| # | 항목 | 영향 | 근거 | 제안 |
|---|---|---|---|---|
| B1 | **Gemini 테마 가산이 종합추천 섹터 축에 들어간다**(최대 +10) | 추천 입력 | `RecommendationService` 섹터 점수 2단계 — AI 스냅샷 종목의 `aiThemes` 개수로 `min(10, 4+2×테마수)`. '점수·순위는 알고리즘, LLM 은 코멘트' 원칙과 충돌(이번 수정 2는 AI 목록 점수만 고쳤다). CLAUDE.md 에 알려진 동작으로 적혀 있다 | 테마 가산 유무를 `signal_outcome` 에 스냅샷해 성적 비교 후 제거 또는 표시 전용으로. 바꾸면 섹터 축 표본 경계 |
| B2 | **12-31 당일부터 미래 행 차단이 무력해진다** | 추천 입력 | 수정 1과 `excludeFutureDated` 모두 날짜 기준이라, 2026-12-31 이 되면 네이버 추정치 행 342개가 '오늘 이전'이 되어 다시 최신 행 자리에 온다 | writer 구분(`market_cap IS NOT NULL` = KIS 일별 행)으로 바꾸거나 추정치 행을 백업 후 삭제(삭제는 사용자 결정). 12월 전에 결정 필요 |
| B3 | **부채비율·순이익률 저장 경로가 사라졌다** | 추천 입력(가치안정성 +2 보너스·저평가 트랙) | 채워진 행 9/10 324 → 9/14~23 약 50 → 9/28 이후 0. 읽는 쪽이 최근 10행에서 값을 찾아 10/12 무렵 부채 항목이 조용히 0 이 된다 | 재무상태표 응답(부채총계)으로 다시 계산해 저장하거나, 못 구하면 결측(null)으로 두고 소비처가 '모름'으로 처리(§4c) |
| B4 | **`per_basis` 를 읽는 곳이 없고 화면 표기도 없다**, PBR 은 지배지분 자본·ROE 는 연결 기준인 행이 있어 ROE÷PBR 같은 조합의 정의가 섞인다 | 표시 + 추천 입력(조합 지표) | 감사 당시 코드 판독(9/30 구현 때 비율 쪽 혼합을 놓쳤다). 해당 행 수 미측정 | 종목 상세·스크리너에 기준 표기, 정의가 섞인 행 수부터 측정 |
| B5 | **종목 AI 분석이 장중 내내 첫 분석으로 고정** | 판단 보조(풀매수/매수 의견·AI 신호·복합신호 'AI 추천') | Redis 사본을 먼저 반환하고 워머가 그 값을 다시 저장, 12·15시 재분석은 메모리에만. 운영 Redis 값은 확인 못 함 | 재분석 결과를 Redis 에 쓰고 응답에 분석 시각 표시. 운영 값부터 확인 |
| B6 | **거래정지 게이트가 마스터 목록 다운로드에 묶여 있다**, 게이트를 안 거치는 생산자가 있다 | 추천 입력·안전 | 다운로드 실패 시 DB 만 보는 거래량 0 정지 판정까지 건너뛴다. AI 분석·스캘핑·차트 타이밍·급등 감지는 게이트 미적용 | 정지 판정을 다운로드와 분리, 새 소비처 체크리스트(§4 '새 스크리너/추천 소비처는 게이트') 적용 |
| B7 | **주간 리포트 누적 비교가 옛 산식 시대와 섞인다** | 측정 | 누적 창에 경계 이전 행이 들어가 '가중치 재조정 후보' 경고가 옛 데이터 기준으로 나올 수 있다 | 주간 리포트도 `currentSample` 경계 사용 |
| B8 | **레거시 `/api/signal-outcomes/accuracy` 가 남아 있다** | 측정(외부 호출 시) | 지난 30일 · 승격 중복 · 레거시 평가값. 이번에 화면 호출은 0 이 됐다 | 제거하거나 `currentSample` 로 교체 |
| B9 | **종목 상세 '핵심 재무 TTM' 라벨 밑에 연간 값** | 표시 | `StockDetailDashboard.vue:237` — 추천이 쓰는 PER 과 다른 숫자 | 라벨을 실제 정의로, 또는 추천과 같은 값 표시 |
| B10 | **동종업계 비교표가 하드코딩** | 표시(§4c) | `StockDetailService.java:2137~` — 실시간 조회 실패 시 하드코딩 값이 '업종 평균 PBR' 에 섞인다 | 실패 시 '데이터 없음' |
| B11 | **결측이 '없음'·'주의'로 보인다** | 표시(§4c) | 수급 0/0 → "외국인·기관 모두 순매도 — 주의", 발굴 트랙 데이터 없음 → "후보 없음" | 조회 실패와 0건을 구분해 표시 |
| B12 | **데이터 기준 시각이 빠져 있다** | 표시 | 🤖 카드에 기준 시각이 없어 월요일 장전에 금요일 픽이 보인다. AI 전략 화면 '기준' 시각은 응답 시각이 아니라 현재 시각으로 덮인다 | 스냅샷 `created_at` 표시 |

**의심되지만 근거가 부족한 것**
- 턴어라운드 스크리너가 재무 표의 PER·PBR·BPS 를 덮어쓰는 코드 경로가 있다. 최근 3일 수집 시간대 밖 수정 행은 0건이었다.
- 종합점수의 결측 보정이 유효 카테고리로 계산된다 — 섹터가 없으면 장중 2점, 기술이 없으면 3점이 붙어 75% 커버리지를 실데이터
  2개로 통과할 수 있다. 주석상 의도된 동작이라 설계 판단이 필요하다.
- CONSOL 265종목 가운데 비지배지분이 큰 회사는 PER 이 낮게 나와 순위에서 유리할 수 있다. 크기 미측정.
- 게이트의 불확실성 폭이 겹치는 3일 창의 상관을 무시해 낙관 쪽으로 나온다. 크기 모름.

**의도된 설계로 확인한 것**
- 평가 제외가 성적을 좋게 만들지 않는다 — 봉 결측으로 빠진 14건의 옛 평균 +0.06% 는 포함된 행(−3.27%)보다 좋았다. 거래정지·
  단위 의심 제외는 0건.
- 낙폭 양수 문제는 영향이 작다(183건 중 7건, 평균 −8.69% → 0 으로 막으면 −8.80%).
- 17:00 기록의 시작가가 NXT 가격인 것은 §4c 시그널 3일 평가 항목에 명시돼 있다.

**보지 못한 영역** — python-backend, 봇 매매 경로, 종목 상세 대부분, FnGuide 값의 정의, 운영 Redis 실제 값, 텔레그램 전체
문구, 2027년 이후 휴장일 달력. 문제를 못 찾은 영역을 안전하다고 보지 않는다.
