# docs/rules — 옛 CLAUDE.md 순서표

2026-11-11 에 `CLAUDE.md` 를 '짧은 규칙 + 이 폴더의 경위 원문'으로 나눴다. 지킬 규칙은 `CLAUDE.md` 에 있고, 이 폴더는 '왜'를 위한 것이다.
아래 표는 옛 `CLAUDE.md` 의 항목 순서다 — 옮긴 문단 안의 '위·아래 항목'·'아래 ⚠ 항목' 같은 말은 이 순서로 푼다.
'있는 곳'이 `CLAUDE.md` 인 항목은 짧아서 그대로 남은 줄이다.

| 번호 | 있는 곳 | 첫머리 |
|---|---|---|
| §1.1 | `CLAUDE.md` | 모든 화면(getQuick/getHeavy/목록)은 공용 StockPriceService.getStockPr… |
| §1.2 | `CLAUDE.md` | 병렬 시세 호출 경로를 새로 만들지 말 것. |
| §2.1 | `CLAUDE.md` | 표시/추천/수급/캐시워밍 = NXT 기준 08:00~20:00 |
| §2.2 | `CLAUDE.md` | 봇/섹터/정규장 판정 = KRX 기준 09:00~15:30(또는 15:40) |
| §2.3 | `CLAUDE.md` | 이 둘을 하나로 "통일"하지 말 것. 경계 구간(08~09시, 15:30~20:00) 동작은 명세대로다. |
| §3.1 | `CLAUDE.md` | ERROR 로깅 + KIS raw 기록 |
| §4.1 | `docs/rules/4-scoring.md` | 실제 만점 58(실적 20 + 수급 기여 10 + 기술 20 + 섹터 8)로 0~100 환산 |
| §4.2 | `docs/rules/4-scoring.md` | 수급 역상관 방어 — composite 총점 수급 캡(A안, P1-6, 2026-07-06, Recommen… |
| §4.3 | `CLAUDE.md` | STRONG_BUY ≥75 / BUY 55~74 / HOLD 40~54 / <40 제외 |
| §4.4 | `CLAUDE.md` | alpha_3d ≥ 0 AND pct_change_3d > 0 |
| §4.5 | `CLAUDE.md` | 스윙 봇과 동기(-3%/+5%) |
| §4.6 | `docs/rules/4-scoring.md` | V30 카테고리 점수 4종(음수=-1 NA sentinel 은 record 경계에서 NULL 변환 — R7/… |
| §4.6-1 | `docs/rules/4-scoring.md` | V50 지수 채널 소비자 제약(불변식) |
| §4.6-2 | `CLAUDE.md` | 지수 축 유효표본 = distinctDays 불변식(P3-11) |
| §4.7 | `CLAUDE.md` | 과열(추격) 페널티 — RecommendationService.overheatPenalty() 단일 출처, … |
| §4.8 | `docs/rules/4-scoring.md` | 발굴 TOP10 정렬 tie-break(recommendationComparator) = 점수 desc → … |
| §4.9 | `docs/rules/4-scoring.md` | 리스크 공시 페널티는 composite 실감점 |
| §4.10 | `docs/rules/4-scoring.md` | AI 스윙·가치(마법의 공식·PEG) 후보는 이익의 질을 거른다(2026-09-30) |
| §4.11 | `CLAUDE.md` | 시장 국면(산식용 regime) |
| §4.12 | `CLAUDE.md` | BULL 섹터 가산은 하나만 |
| §4.13 | `docs/rules/4-scoring.md` | 신규 진입 감점 임계(applyNewEntryPenalty/newEntryPenaltyThreshold) |
| §4b.1 | `CLAUDE.md` | 일캐시(종목·일자 1회) |
| §4b.2 | `CLAUDE.md` | 신규 분류 시에만 |
| §4b.3 | `docs/rules/4b-catalyst-gemini.md` | 상한 5종목 |
| §4b.4 | `CLAUDE.md` | 근거 기사 링크(V53, 2026-08-20) |
| §4b.5 | `CLAUDE.md` | 최근 공시 목록(2026-08-20) |
| §4b.6 | `CLAUDE.md` | ⚠ 뉴스 fetch 파이프라인 함정 3종(2026-07-01, 7일 연속 100% NONE 장애 — 세 원인… |
| §4b.6-1 | `CLAUDE.md` | 네이버 키 배선 |
| §4b.6-2 | `CLAUDE.md` | URL 인코딩 |
| §4b.6-3 | `CLAUDE.md` | §4c 하드닝 |
| §4b.7 | `docs/rules/4b-catalyst-gemini.md` | Gemini quota — 무료 티어 rate 제한(2026-07-01 해소) |
| §4c.1 | `CLAUDE.md` | 체결강도 |
| §4c.2 | `CLAUDE.md` | 시장 진단 condition 은 ADR(20일) 기반만 |
| §4c.3 | `CLAUDE.md` | 섹터 거래대금은 실측만 |
| §4c.4 | `docs/rules/4c-market-display.md` | python-backend 재편 (2026-06-11) |
| §4c.5 | `docs/rules/4c-market-display.md` | 차트기법 신호 (2026-06-29) |
| §4c.6 | `CLAUDE.md` | 수급 채점 노후 가드(2026-07-28) |
| §4c.7 | `docs/rules/4c-supply-short.md` | 투자자 순매수 '순위'는 확정치가 아니라 가집계다 — 14:30 입력이 마지막이다(2026-11-04, 네이… |
| §4c.8 | `docs/rules/4c-supply-short.md` | 투자자 수급의 단일 출처는 KIS — KRX 보충 은퇴(2026-09-28) |
| §4c.9 | `docs/rules/4c-calendar.md` | 휴장일 달력(2026-08-21) |
| §4c.10 | `docs/rules/4c-market-display.md` | DART 위험 체크는 실패≠안전(2026-07-28) |
| §4c.11 | `docs/rules/4c-financial.md` | 재무 테이블에는 행 종류가 두 가지 섞여 있다 — 하나로 보지 말 것(2026-08-26) |
| §4c.12 | `docs/rules/4c-financial.md` | 미래 날짜 재무 행은 읽는 쪽에서 거른다(2026-09-02) |
| §4c.13 | `docs/rules/4c-kis-api.md` | 🔴→✅ KIS 재무 API 는 tr_id 로 디스패치한다 — 경로가 아니다(2026-08-27) |
| §4c.14 | `docs/rules/4c-kis-api.md` | KIS 분봉 API(FHKST03010200)는 FID_ETC_CLS_CODE 를 빈값이라도 반드시 보낸다(… |
| §4c.15 | `docs/rules/4c-kis-api.md` | KIS 종목별 프로그램매매는 program-trade-by-stock · FHPPG04650101 — 응답은… |
| §4c.16 | `docs/rules/4c-kis-api.md` | 지수 일봉의 마지막 봉은 장 시작 전·장중엔 '오늘' 형성 봉이다 — 측정 스냅샷은 마감 확정 봉만 쓴다(2… |
| §4c.17 | `docs/rules/4c-kis-api.md` | 등락률 0% 는 결측이 아니다 — 다른 시점 값으로 덮지 말 것(2026-09-17 감사 F3) |
| §4c.18 | `docs/rules/4c-kis-api.md` | KIS 는 만료 토큰에 401 이 아니라 500 + EGW00123 을 준다(2026-09-08~09 실측) |
| §4c.19 | `docs/rules/4c-kis-api.md` | KIS 접근토큰 발급은 분당 1회 — 토큰은 Redis 로 프로세스를 넘긴다(2026-09-11) |
| §4c.20 | `docs/rules/4c-financial.md` | 적자를 분모로 한 변화율은 성장률이 아니다(2026-08-28) |
| §4c.21 | `docs/rules/4c-financial.md` | stock_financial_data 는 writer 가 둘 — 일괄 UPDATE 금지(2026-08-28,… |
| §4c.22 | `docs/rules/4c-financial.md` | KIS 재무 금액 원본 단위는 억원 — /100 하지 말 것(2026-08-28, V56) |
| §4c.23 | `docs/rules/4c-financial.md` | KIS 분기 손익은 연결재무제표 회사만 이어서 준다 — 0 행은 실적 0 이 아니고, 묵은 4분기는 TTM … |
| §4c.24 | `docs/rules/4c-financial.md` | DART 금액은 그 회사의 표시 통화다 — 원화가 아니면 쓰지 않는다(2026-11-04, 네이버 대조로 발… |
| §4c.25 | `docs/rules/4c-financial.md` | 분기 재무 누적(YTD) 판정은 이력 전체로 한다(2026-08-27) |
| §4c.26 | `docs/rules/4c-financial.md` | 분기 재무 단일 출처 = V55 stock_quarterly_financial(2026-09-23 단일화, … |
| §4c.27 | `docs/rules/4c-financial.md` | 재무 수집 유니버스는 합집합(2026-08-26, R5) |
| §4c.28 | `CLAUDE.md` | 실적 서프라이즈 POSITIVE 는 흑자(latest>0) 필수 + 인접 분기(≤120일)만 비교 |
| §4c.29 | `docs/rules/4c-financial.md` | 점수를 주는 실적 판정은 전년 동기보다도 좋아졌을 때만(2026-09-29) |
| §4c.30 | `docs/rules/4c-financial.md` | 성장률 4종(매출·순이익·EPS·PEG)은 분기 원본 TTM 전년 동기 대비 — 2단계 배치 단독, 매일 덮… |
| §4c.31 | `docs/rules/4c-signal.md` | 시그널 3일 평가의 정의가 흔들렸다 → V59 교정 컬럼 d3_*(2026-09-17) |
| §4c.32 | `docs/rules/4c-kis-api.md` | KIS 일봉의 '그날' 행은 다음 날 아침에야 KRX 종가다 — 저녁 값을 종가로 쓰지 말 것(2026-11… |
| §4c.33 | `CLAUDE.md` | 시그널 평가 give-up 창(2026-07-28) |
| §4c.34 | `docs/rules/4c-signal.md` | 시그널 측정 위생(2026-08-21, 같은 날 적대적 리뷰로 재보강) — 되돌리지 말 것 |
| §4c.35 | `docs/rules/4c-signal.md` | 20:05 마감 스냅샷은 시그널을 기록하지 않는다 — 기록은 장중 스냅샷(11:30·14:00·17:00)만… |
| §4c.36 | `docs/rules/4c-signal.md` | 시그널을 기록하는 스냅샷(11:30·14:00·17:00)은 컷 통과 후보의 시세를 그 자리에서 받는다(20… |
| §4c.37 | `docs/rules/4c-signal.md` | 주 1회 크론은 놓치면 영영 못 돈다 — 따라잡기 필수(2026-08-28) |
| §4c.38 | `docs/rules/4c-financial.md` | stock_financial_data 의 비율 컬럼은 0 이 결측일 수 있다(2026-08-31 실측) |
| §4c.39 | `docs/rules/4c-signal.md` | 오래된 추천을 현재 매수 결론으로 재사용하지 않는다(2026-09-17 감사 F4/F5/F6) |
| §4c.40 | `docs/rules/4c-supply-short.md` | 공매도는 KIS '거래 비중'만 있다 — 표시 전용(2026-10-02, 사용자 결정 "거래 비중으로, 표시… |
| §4c.41 | `docs/rules/4c-market-display.md` | 시장 폭(상승·하락 종목 수)·ADR 은 KIS 국내업종 현재지수 — 실패는 0 이 아니라 '없는 날'(20… |
| §4c.42 | `docs/rules/4c-sweeps/2026-10-02-labels.md` | 이름표는 데이터가 가진 날짜·정의로(2026-10-02 화면 점검) |
| §4c.43 | `docs/rules/4c-sweeps/2026-10-02-afternoon.md` | 같은 날 오후 화면 전수 재점검에서 더 나온 것(2026-10-02) |
| §4c.44 | `docs/rules/4c-market-display.md` | 화면의 USD/KRW 는 글로벌 시세 한 곳 — GlobalFuturesService 의 KRW(Yahoo … |
| §4c.45 | `docs/rules/4c-market-display.md` | 종목 마스터 코드는 영숫자 6자리 그대로 — 숫자만 남기지 말 것(2026-10-02 화면 점검) |
| §4c.46 | `docs/rules/4c-financial.md` | 재무 행 market 은 마스터 기준 — 코드 첫 글자로 짐작하지 않는다(2026-10-02) |
| §4c.47 | `docs/rules/4c-market-display.md` | AI 전략 후보가 없으면 그 회차는 저장하지 않는다 — 대체 목록 금지(2026-10-02) |
| §4c.48 | `docs/rules/4c-sweeps/2026-10-03-sweep3.md` | 전수 점검 3차(2026-10-03) — 부호·단위·응답 모양·실패 표시·이름표. 되돌리지 말 것 |
| §4c.49 | `docs/rules/4c-sweeps/2026-10-04-sweep3-followup.md` | 전수 점검 3차 후속(2026-10-04) — 되돌리지 말 것 |
| §4c.50 | `docs/rules/4c-sweeps/2026-10-06-screen4.md` | 화면 점검 4차(2026-10-06, 표본 시작일) — 되돌리지 말 것 |
| §4c.51 | `docs/rules/4c-sweeps/2026-10-07-screen5.md` | 화면 점검 5차(2026-10-07, 로그인 화면) — 되돌리지 말 것 |
| §4c.52 | `docs/rules/4c-sweeps/2026-10-19-offline.md` | 서버 없이 한 점검(2026-10-19, 10/7 50커밋 재검토) — 되돌리지 말 것 |
| §4c.53 | `docs/rules/4c-market-display.md` | 16:00 일일 리포트는 소수 자리를 줄일 때 반올림 방식을 준다(2026-11-04) |
| §4c.54 | `docs/rules/4c-sweeps/2026-11-04-screen6.md` | 화면 점검 6차(2026-11-04, 로그인 화면) — 되돌리지 말 것 |
| §4c.55 | `docs/rules/4c-calendar.md` | 장이 늦게 열리고 늦게 닫히는 날은 달력의 표가 정한다(2026-11-04) |
| §4c.56 | `docs/rules/4c-sweeps/2026-11-05-hardcoded.md` | 하드코딩 표·외부 주소 전수 대조(2026-11-05) — 되돌리지 말 것 |
| §4c.57 | `docs/rules/4c-sweeps/2026-11-06-screen7.md` | 화면 점검 7차(2026-11-06, 장 시작 전 로그인 화면 — 전날 코스피 −5.28%·간밤 나스닥 −6… |
| §4c.58 | `docs/rules/4c-sweeps/2026-11-09-screen8.md` | 화면 점검 8차(2026-11-09, 장 마감 뒤 로그인 화면 — 하락장·후보 0건) — 되돌리지 말 것 |
| §4c.59 | `docs/rules/4c-sweeps/2026-11-10-morning.md` | 11/10 아침 점검(장 시작 전 로그인 화면, 사용자 '다 너가 추천하는 방향으로 알아서해줘') — 되돌리… |
| §4c.60 | `docs/rules/4c-market-display.md` | null 충돌 스캔(2026-11-04) — 지금은 없다 |
| §4c.61 | `CLAUDE.md` | 표시 계약 |
| §4d.1 | `CLAUDE.md` | 재시작 reconciliation |
| §4d.2 | `CLAUDE.md` | 매도 체결 확인 |
| §4d.2-1 | `CLAUDE.md` | 불변식 |
| §4d.2-2 | `CLAUDE.md` | Phase 2(미체결 잔여분 능동 취소 order-rvsecncl)는 미구현 — 주문변경 API라 모의계좌 … |
| §4d.2-3 | `CLAUDE.md` | ⚠ inquireDailyCcld 응답 필드(output1/odno/tot_ccld_qty)·TR_ID 는 … |
| §4d.3 | `docs/rules/4d-bot-orders.md` | 분할익절도 체결을 확인한다 — 접수 ≠ 완료(2026-09-17 감사 F1, V60) |
| §4d.4 | `CLAUDE.md` | KIS 주문 성공 + 로컬 DB 저장 실패 = 즉시 killswitch |
| §4d.5 | `docs/rules/4d-bot-orders.md` | 봇 진입 가격 sanity 가드 |
| §4d.6 | `docs/rules/4d-bot-orders.md` | KIS 잔고 조회는 단일 비행(2026-09-02, RealTradeService.getBalanceInfo… |
| §4d.7 | `docs/rules/4d-bot-orders.md` | 일일 손실 서킷브레이커 |
| §4e.1 | `CLAUDE.md` | 흐름: 관리자가 영상(허용된 YouTube 주소의 ID)·게시 시각·자막 출처·출연자 등록 → 타임스탬프 자… |
| §4e.2 | `docs/rules/4e-youtube-opinion.md` | 자동 수집은 연결하지 않았다(의도) |
| §4e.3 | `docs/rules/4e-youtube-opinion.md` | 불변식 |
| §4e.4 | `docs/rules/4e-youtube-opinion.md` | 분석기 선택 — Claude 는 로컬 작업자만(2026-10-01, V66) |
| §4e.5 | `CLAUDE.md` | 운영 스키마 대조 |
| §5.1 | `CLAUDE.md` | fail-open |
| §5.2 | `CLAUDE.md` | Clock 주입 |
| §5.3 | `CLAUDE.md` | 단 시세(StockPriceService.getStockPrice)는 예외 — L1 로컬(Concurrent… |
| §5.4 | `CLAUDE.md` | cron 시각들은 튜닝된 값이다. 근거 없이 바꾸지 말 것. |
| §5.5 | `docs/rules/5-infra.md` | backend 컨테이너 메모리·OOM 처리(2026-09-15 사고) |
| §5.6 | `docs/rules/5-infra.md` | 서버 감시·백업 보강(2026-11-10, 개선 후보 5번 — docs/OPS_WATCH_BACKUP.md) |
| §5.7 | `docs/rules/5-infra.md` | 트랜잭션을 끊은 범위(NOT_SUPPORTED) 안에서 부르는 조회 메서드엔 @Transactional(re… |
| §5.8 | `docs/rules/5-infra.md` | 시세 캐시 표 stock_price 는 '종목별 최신 1행'과 '최근 7일'만 읽힌다 — 보존 잡은 기본 꺼… |
| §5.9 | `docs/rules/5-infra.md` | 주기 잡의 "정상" 로그는 반복하지 않는다(2026-08-31) |
| §5.10 | `docs/rules/5-infra.md` | 백엔드 jar 에는 화면이 없다 — 화면은 nginx + frontend/dist(2026-10-02) |
| §5.11 | `docs/rules/5-infra.md` | 배포는 한 번에 하나, 떠 있는 커밋은 라벨로 확인한다(2026-10-06) |
| §5.12 | `docs/rules/5-infra.md` | 장중 배포 가드와 재시작 기록(2026-11-10) |
| §5.13 | `docs/rules/5-infra.md` | 헬스체크는 앱 자신만 본다 — 메일 지시자는 껐다(2026-11-04) |
| §5.14 | `docs/rules/5-infra.md` | 밖에서 부를 일이 없는 로그인 없는 주소는 nginx 가 막는다(2026-11-10) |
| §5.15 | `docs/rules/5-infra.md` | API 권한은 세 층이다 — 공개 목록·서버 안·관리자(2026-11-10) |
| §6.1 | `CLAUDE.md` | 인증 필터는 Access Token 만 통과 |
| §6.2 | `CLAUDE.md` | 라우터 가드는 RT 보존 |
| §6.3 | `CLAUDE.md` | authAPI 는 apiClient 경유 |
| §6.4 | `CLAUDE.md` | 로그아웃은 서버 토큰도 삭제 |
| §6.5 | `CLAUDE.md` | RT 는 기기별 분리 + 회전 유예 60초 |
| §6.6 | `CLAUDE.md` | RealTimeDataCache.updateMinuteBar |
| §7.1 | `docs/rules/7-control-room.md` | (ADMIN) — 판정 캘린더·KPI·FLAGGED 조회 + AI 크루(에렌/SCOUT/FIREWALL) 5… |
| §7.2 | `docs/rules/7-control-room.md` | KPI 6종 중 '재무 입력층'은 조용한 죽음 감지기다(2026-08-26 신설) |
| §7.3 | `docs/rules/7-control-room.md` | 데이터 이상 점검은 규칙이 하고 크루는 읽기만 한다(2026-08-27, 8/28 확장) |
| §7.4 | `docs/rules/7-control-room.md` | 불변식 |
| §7.5 | `CLAUDE.md` | 문서에서 읽는다 |
| §7.6 | `CLAUDE.md` | 원 단위 |
| §7.7 | `docs/rules/7-control-room.md` | 5턴 고정 |
| §7.8 | `docs/rules/7-control-room.md` | "믿고 사도 되나" 게이트(⑦ KPI, 2026-09-16) |
| §7.8-1 | `docs/rules/7-control-room.md` | 게이트는 2026-09-21 부터 V59 교정값(d3_*)을 읽는다 — 그날이 표본 경계다. |
| §7.8-2 | `docs/rules/7-control-room.md` | 표본 시작일은 기본 '미정' — 수정 반영을 확인한 뒤 정한다(SignalSampleBoundary 단일 출… |
| §7.8-3 | `docs/rules/7-control-room.md` | "봇을 믿고 맡겨도 되나" 게이트(⑧ KPI, 2026-10-02) |
| §7.8-4 | `docs/rules/7-control-room.md` | 장중 재시작 카드(⑨ KPI, 2026-11-10) |
| §7.8-5 | `docs/rules/7-control-room.md` | 봇 모의매매는 이 게이트의 입력이 아니다(2026-09-16 실측) |
| §7.9 | `CLAUDE.md` | 권한은 SecurityConfig URL 규칙이 담당 |
| §7.10 | `docs/rules/7-control-room.md` | 관제실 화면 이동 바(2026-08-27) |
| §7.11 | `CLAUDE.md` | 프론트 IA 규칙("새 주식 화면 금지 → 탭에 흡수")의 명시적 예외 |
| §7.12 | `CLAUDE.md` | 상세: docs/CONTROL_ROOM.md |
| §ia.1 | `CLAUDE.md` | GNB 4탭: 오늘/시장/발굴/매매 |
| §ia.2 | `CLAUDE.md` | '오늘' 탭 |
| §ia.2-1 | `docs/rules/ia-frontend.md` | 시간대신호(장전/장후)·실시간수급(장중)·관심종목 = '오늘' 탭(2026-07-01, 발굴 슬림화 A 1단… |
| §ia.3 | `CLAUDE.md` | 역할 분리(2026-06-23~24): 오늘=모멘텀, 발굴=다각도 선별. |
| §ia.4 | `docs/rules/ia-frontend.md` | 차트 기법 통합 시 발굴/매수후보 스코어러는 항상 분리한다(2026-06-29). |
| §ia.5 | `docs/rules/ia-frontend.md` | 발굴 = 종합판단 중심 축소(2026-07-01) |
| §ia.5-1 | `CLAUDE.md` | 💎저평가 getValueTop10 (PBR·ROE·부채·흑자) / 🚀성장 getGrowthTop10 (매출·… |
| §ia.5-2 | `CLAUDE.md` | lazy 로드 |
| §ia.6 | `docs/rules/ia-frontend.md` | 허브 60초 갱신은 보이는 탭의 데이터만(2026-10-07, StockTradingDashboardV2._… |
| §ia.6-1 | `CLAUDE.md` | 재사용 |
| §ia.7 | `CLAUDE.md` | 결론 카드(StockConclusionCard.vue): 매매계획(손절/목표가+MFE/MAE) · 점수대 적… |
