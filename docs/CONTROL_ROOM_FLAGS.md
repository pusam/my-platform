# 관제실 FLAGGED — 열려 있는 이상 항목

> 관제실(`/control-room`) 우측 상단 **⚠ FLAGGED** 패널이 읽는 **유일한 소스**다.
> **사람이 손으로 관리한다** — 감사·리뷰에서 발견한 항목을 여기에 적고, 해소되면 지운다.
>
> ⚠ **해소된 항목을 남겨두지 말 것.** 2026-08-24 최초 작성 시 목업이 들고 있던 5건 중 3건이
> 이미 해소된 상태였다(`pattern-detection.enabled` 배선 완료 / CLAUDE.md 모순 해소 / V52 문서 등록 완료).
> 낡은 플래그는 관제실 첫 화면을 거짓말로 만들고, 크루(FIREWALL)가 그걸 근거로 반려 판단을 내린다.
>
> **"판정이 밀렸다"류는 여기 적지 않는다** — 미판정 건수·OVERDUE 는 `SCHEDULE_DECISIONS.md` 의
> 판정 기록 표와 캘린더 YAML 에서 **결정적으로 계산**된다. 손으로 중복 기록하면 두 값이 어긋난다.
>
> **스키마**
> | 필드 | 필수 | 값 |
> |---|---|---|
> | `id` | ✅ | kebab-case 고유 식별자 |
> | `severity` | ✅ | `critical` \| `warning` \| `info` — 화면 좌측 색띠(적/황/청) |
> | `title` | ✅ | 한 줄 제목 |
> | `key` | | 제목 옆 배지(파일명·티켓·심볼 등) |
> | `body` | ✅ | 무엇이 문제이고 무엇을 해야 하는지 |
> | `recorded_on` | ✅ | 기록일 `YYYY-MM-DD` — **오래된 항목의 신선도 판단용** |
> | `ref` | | 근거 문서·코드 위치 |
>
> 파싱 실패 항목은 조용히 건너뛰지 않고 `파싱 오류: <id>` 로 노출된다(§4c).
> 파일이 없거나 블록이 비면 "플래그 0건"이 아니라 **"플래그 데이터 없음"** 으로 표시된다.

```yaml
flags:
  - id: external-feed-sweep-2026-08-31
    severity: info
    title: 외부 소스 생존 스위프 결과 — 죽은 곳 2, 산식 영향 없음
    key: 외부소스
    body: >
      KRX 건(죽은 엔드포인트가 몇 달 조용히 썩음)을 계기로 코드 안 외부 HTTP 엔드포인트를
      전수 시험했다(2026-08-31, 키 필요한 DART·네이버검색·KIS 는 로그로 생존 확인).
      죽은 곳 — ① CNN 공포탐욕(418 "You're a bot" 봇차단): GlobalFuturesPage 표시 전용,
      실패 처리 정직(success:false, 카드 숨김), 산식 무관. 살리려면 대체 소스가 필요하다.
      ② 한경 RSS(403 Cloudflare): NewsService 3개 피드 중 하나 — 매경·etnews 는 정상이라
      뉴스 흐름 유지. 살아있는 곳 — wisereport(분기재무 크롤 92KB)·m.stock API·
      야후차트(간밤미국장)·네이버 시세크롤(ADR)·구글뉴스RSS·매경·etnews·KIS마스터·KIND.
      조치 불요 — 둘 다 §4c 준수 확인됨. 대체 소스를 찾으면 그때 별건으로.
    recorded_on: 2026-08-31
    ref: GlobalFuturesService.getFearGreedIndex, NewsService RSS_FEEDS

  - id: krx-feeds-dead-remaining
    severity: warning
    title: 남은 KRX 소비자 2곳도 같은 이유로 죽어 있다 (영향은 제한적)
    key: KRX잔여
    body: >
      상장목록을 고치며 같이 확인한 것. 둘 다 급하지 않지만 "살아있다"고 착각하면 안 된다.
      ① MarketTimingService.getKrxOtp — 같은 없는 주소를 쓴다. 소비처는 수동 백필
      (collectHistoricalMarketData)뿐이고, 일일 ADR 은 네이버 크롤 경로라 무관하다.
      0/0/0 위장 저장은 2026-08-31 수정 — 실패 시 그 날짜를 '실패'로 집계하고 건너뛴다(§4c).
      백필 기능 자체는 여전히 死(살리려면 KRX 아닌 등락 수 소스 필요) — 쓸 일이 생기면 그때 별건.
      ② InvestorDailyTradeService.collectPensionFromKrx — 단발 getJsonData(LOGOUT)라 死.
      수급 주 소스는 KIS(KisInvestorDataCollector)라 외국인·기관은 정상이고, 이건 연기금 보충망이다.
      결과: KIS 가 연기금 빈 응답을 줄 때의 안전망이 없고, **KOSDAQ 연기금은 구조적으로 0건**
      (KIS 는 KOSPI 만 준다). 수급 점수는 외국인·기관 위주라 즉시 영향은 작다.
    recorded_on: 2026-08-31
    ref: MarketTimingService.getKrxOtp, InvestorDailyTradeService.collectPensionFromKrx

  - id: weekly-report-week-hole-2026-08-16
    severity: warning
    title: 주간 리포트에 8/16 주 구멍 — 따라잡기로는 안 메워진다
    key: 주간측정
    body: >
      2026-08-28 dead-man switch 경보(11.6일 경과)의 실체. 원인은 8/20~24 서버 다운으로
      8/23(일) 18:00 크론이 통째로 빠진 것이다. 같은 날 따라잡기 크론(월~토 18:30)을 넣었으나
      **그것으로 8/16 주가 복구되지는 않는다** — resolveTargetWeekEnd 는 today 기준이라
      8/23 크론이 만들었을 주(8/10~8/16)와 8/28 따라잡기가 만드는 주(8/17~8/23)가 다르다.
      즉 weekly 스냅샷 시계열에 8/16 주 한 칸이 비어 있고, 12주 추세(findTop12)를 볼 때
      그 구멍이 그대로 보인다.
      메우려면 generateWeeklyReport 에 대상 주를 파라미터로 넘길 수 있어야 하는데,
      그때 cumulative 를 '그 주 시점'으로 계산할지 '오늘 시점'으로 할지가 측정 의미를 바꾼다 —
      산식 판단이라 사람이 정할 일이다. 그때까지 12주 추세 해석 시 8/16 결측을 인지할 것.
    recorded_on: 2026-08-28
    ref: SignalWeeklyReportService.weeklyReportCatchUp, SignalWeeklyReportServiceTest catchUpFillsCurrentTargetNotTheMissedWeek

  - id: earnings-source-switched-2026-08-28
    severity: info
    title: 실적 분기소스 켬 (2026-08-28) — 이 날짜가 측정 표본의 경계다
    key: R1
    body: >
      recommendation.earnings.quarterly-source 를 true 로 전환했다. 전환 시점 실측 —
      커버리지 2,289종목 · TURNAROUND 209→103(연속 적자 조건으로 한 분기 삐끗 106건 제거) ·
      POSITIVE 625 · NEGATIVE 610 · 임계 ±20% 미변경.
      **2026-08-28 이후 시그널만 "현재 산식의 성적"이다.** 이전 표본은 earnings 가
      ~90종목으로만 돌던 시기라 섞으면 안 된다 — 밴드 적중률·대조군 비교·주간 리포트 해석 시 인지할 것.
      임계(±20%)는 일부러 안 건드렸다. 어느 값이 옳은지는 forward 성과가 필요하고,
      근거 없이 올리면 출처 없는 상수가 하나 더 생긴다. 재판정일 2026-10-05.
      되돌리려면 .env 에서 그 줄을 지우고 docker compose up -d backend (restart 아님).
    recorded_on: 2026-08-28
    ref: docs/SCHEDULE_DECISIONS.md 판정 기록 2026-08-28

  - id: future-dated-annual-rows
    severity: info
    title: 재무 테이블에 미래 날짜(2026-12-31) 행이 342건
    key: 데이터위생
    body: >
      report_date 최댓값이 미래인 2026-12-31 이고 342행 있다(코드 주석의 "미래 일자 12-31 annual row").
      어닝 서프라이즈는 120일 인접분기 가드가 막아 무해하지만, "최신 행"을 집는 다른 소비자가
      생기면 미래 연간 행을 오늘 값으로 쓰게 된다. 새 소비자를 붙일 때 report_date <= 오늘 조건을 확인할 것.
    recorded_on: 2026-08-26
    ref: stock_financial_data report_date 분포 실측

  - id: real-cash-field-unverified
    severity: warning
    title: REAL 가용현금 필드가 D+1 정산값 — 당일 매수 미반영 의심
    key: nxdy_excc_amt
    body: >
      봇 가용현금이 nxdy_excc_amt(익일정산)인데 T+2 결제라 당일 매수가 반영되지 않으면 ① 같은 현금으로
      주문이 두 번 통과해 미수 ② 총자산이 부풀려져 -3% 킬스위치가 늦게 발동. 코드만으로 확정 불가 —
      실전 매수 1건 전후 [KIS 잔고·현금필드 진단] 로그에서 dnca_tot_amt / nxdy_excc_amt /
      prvs_rcdl_excc_amt 중 어느 값이 즉시 감소하는지 확인 후 그 필드로 교체. 추측 교체 금지(주문 전면 차단 위험).
    recorded_on: 2026-08-05
    ref: KoreaInvestmentService.java:1141 logCashFieldsForAudit

  - id: pattern-rejection-not-persisted
    severity: warning
    title: V52 패턴 shadow 의 기각 후보가 DB 에 없음
    key: V52
    body: >
      RejectionStats 결과가 로그로만 남아 컨테이너 재생성마다 소실된다. 9/16 승격 판정에서 기각군 대비가
      필요하면 감지기 오프라인 재실행(순수 함수 + 일봉만 사용)으로 재구성해야 한다.
    recorded_on: 2026-08-05
    ref: VERIFICATION_BACKLOG P2-20

  - id: judgment-layer-p1
    severity: warning
    title: 매수 판단 계층 P1 3건 미수정 (R13 / R14 / R15)
    key: R13-R15
    body: >
      R13 결론카드가 30일 노후 스냅샷에 오늘 가격을 합성. R14 룰3 이 총점 없이 수급 역상관 축만으로 BUY 승격.
      R15 체크리스트가 노후 연속매수·공매도·fail-open 상태를 ✅ 로 표시. 셋 다 화면이 실제보다 확신을 준다.
    recorded_on: 2026-08-21
    ref: VERIFICATION_BACKLOG "AUDIT 2026-08-21" R13~R15

  - id: outage-2026-08-20-measurement-gap
    severity: warning
    title: 2026-08-20~24 서버 다운으로 수집·측정에 5일 공백
    key: 표본공백
    body: >
      호스트 다운(8/20 오후~8/24 12:04) 동안 수집 배치가 전혀 돌지 않아 수급·가격·시그널·대조군 표본에
      5일 구멍이 있다. 복구 후 자동으로 메워지지 않는 축(그날그날 스냅샷을 쌓는 것들)은 영구 결손이다.
      대조군 첫 판정(확인일 2026-09-07, 트리거 n>=30) 전에 그 구간을 제외할지 포함할지 먼저 정할 것 —
      "쌓였다"고도 "0"이라고도 단정하지 말고 실제 행 수를 확인해야 한다.
      복구 당일(8/24) 08:30 종목상태(KRX 동기화)도 건너뛰어 그날 하루는 거래정지 게이트가 fail-open 이었다.
    recorded_on: 2026-08-25
    ref: memory/audit-2026-08-21.md, DiagnosticsController /api/diagnostics/data

  - id: sample-influx-baseline-shift
    severity: warning
    title: 표본 유입 기준선이 2026-07-28 부로 바뀌었다 (붕괴 아님, 부풀림 제거)
    key: 기준선
    body: >
      시그널 유입이 7월 일 4~10건에서 8월 일 1~2건으로 떨어졌다. 원인은 2026-07-28 배포다 —
      추천 신뢰성 감사 12건(8523ed1, 후보를 깎는 방향) + 어제 스냅샷 무한 노출 수정(16a1589).
      후자 때문에 7/28 이전에는 컷 통과 0건일 때 어제 후보가 종일 재노출돼 같은 후보가 반복
      기록됐다. 즉 7월 수치가 부풀려진 것이고 지금이 정상이다.
      → 7/28 이전 유입을 기준선으로 쓰지 말 것. "유입이 회복되면 판정한다"는 전제는 성립하지 않는다.
      표본 기반 판정(대조군 n>=30 / RVOL / 캡10)은 전부 이 속도에 종속되므로 기한도 이 기준으로 잡는다.
    recorded_on: 2026-08-26
    ref: git 8523ed1 · 16a1589, docs/SCHEDULE_DECISIONS.md 표본 유입 기준선

  - id: control-group-universe-asymmetry
    severity: warning
    title: 대조군 비대칭 수정됨(8/31) — 표본 경계 9/1, 첫 판정 연기
    key: 대조군
    body: >
      2026-08-31 해소(사용자 결정 ① — 편향된 기준선으로 낼 첫 판정은 어차피 다시 해야 한다).
      비대칭 두 가지를 대칭화 — ① 유니버스에 최근성 조건(마지막 봉 7일 이내): 수집 끊긴 종목은
      시그널이 나올 수 없는데 대조군에는 뽑혔다 ② 시세를 시그널과 같은 5분 신선 캐시 전용으로
      (이전엔 대조군만 신규 KIS 호출까지 감행해 죽은/저유동 종목 가격을 떠왔다).
      **측정 표본 경계 = 2026-09-01** — 그 전 대조군은 base rate 가 낮은 쪽으로 편향(edge 과대 방향)이라
      비교창을 섞으면 안 된다. 첫 판정은 9/1 이후 표본으로 양쪽 n>=30 재충족 시(9월 하순 예상).
      확인 — 배포 후 CONTROL_RANDOM 유입이 계속되는지(엄격해진 게이트로 표본 유입이 다소 줄 수 있음,
      8회→12회 재시도로 완화), 오염가드(signal_score IS NOT NULL = 0건)는 불변인지.
      ⚠ 9월 하순 첫 판정 후 이 항목을 지울 것.
    recorded_on: 2026-08-31
    ref: ControlGroupService, StockPriceHistoryRepository.findActiveStockCodesWithMinHistory
  - id: vkospi-index-0503-not-volatility
    severity: critical
    title: VKOSPI(업종 0503)가 변동성 지수가 아니다 — 매크로 tilt 가 도입 이래 100% RISK_OFF
    key: MacroTiltService
    body: >
      2026-09-22 실측. macro_tilt_snapshot 54행(2026-07-07~09-21) 전부 vkospi>=30 이라
      classifyMacroRegime 의 "VKOSPI>=30 -> RISK_OFF 공포 강제" 1축 오버라이드가 매번 걸려
      tilt 가 54/54 RISK_OFF 다. 상수는 데이터가 아니다.
      근거 — 값 범위 39.33~87.90(평균 65.76)로 변동성 지수 수준이 아니고, 같은 날 KOSPI 종가와
      일간 변화율 상관이 -0.037(레벨 -0.140)이다. 진짜 변동성 지수면 -0.5~-0.8 이어야 한다.
      같은 기간 KOSPI 는 5594~7656 으로 37% 출렁였는데 이 계열은 49->47->46->45->44->43 으로
      매끈하게 흐르고 52일간 10% 이상 일간 점프가 2회뿐이다.
      기준일 자체는 정상(vkospi_date = snapshot_date, 당일).
      확인할 것 — 업종코드 0503 이 KIS 지수시세 TR(FHPUP02120000)에서 무엇으로 해석되는지.
      코드 주석은 지수 마스터(idxcode.mst)의 "00503VKOSPI" 를 근거로 들지만, KIS 는 틀린 요청에도
      200 을 주는 API 다(§4c 재무 tr_id / 분봉 파라미터 건과 같은 부류) — 마스터 코드 공간과
      시세 TR 코드 공간이 다를 수 있다. 임의의 지수코드를 조회할 수 있는 경로가 없어 미확정.
      ⚠ 임계(30) 를 성적에 맞춰 조정하지 말 것 — 입력이 무엇인지 먼저 확정해야 한다.
    recorded_on: 2026-09-22
    ref: MacroTiltService.VKOSPI_INDEX_CODE, classifyMacroRegime, macro_tilt_snapshot

  - id: growth-batch-step4-silent-skip
    severity: warning
    title: 성장률 배치(올인원 4단계)가 수집일의 27% 에서 조용히 빠진다
    key: AsyncCrawlerService
    body: >
      2026-09-22 실측. stock_financial_data 일별 스냅샷에서 eps_growth / revenue_growth /
      profit_growth / peg 네 컬럼이 같은 날 통째로 0 이 되는 날이 있다 — 최근 11 수집일 중
      2026-09-21, 09-17, 09-11 세 날(27%). 같은 날 영업이익률 2,295행 · 매출 2,192행은 정상이라
      1~3단계는 완주했고 4단계(calculateAndUpdateGrowthRates)만 빠진 것이다.
      ⚠ 이 실패는 로그에 흔적이 없다 — 08:30/15:38 스케줄러는 시작 줄만 남기고,
      비동기 본체(collectAllInOneAsync)는 진행상황을 sseEmitterService 로만 보낸다.
      즉 화면을 보고 있지 않으면 어느 단계에서 죽었는지 아무도 모른다(§4c 침묵 금지).
      ⚠ 2026-09-22 08:30 에 원인 하나를 직접 관찰했다 — 배포로 컨테이너가 재생성되자
      진행 중이던 올인원이 통째로 사라졌고, 재기동 후 로그에는 그 회차의 흔적이 한 줄도 없다.
      재시도도 없다(다음 기회는 15:38). 즉 배치 시간대의 배포·재시작·OOM 이 같은 결과를 낸다.
      ① ②는 2026-09-22 에 처리했다 — 단계별 INFO 로그 4줄(4단계는 0건도 찍는다)과
      비동기 본체의 catch 에서 batchMonitor.alertFailure. 다음 회차부터 어디서 멈췄는지 보인다.
      ⚠ 남은 것은 ③ — 컨테이너가 죽으면 catch 도 안 돈다. '시작했는데 끝나지 않았다'는
      시작/완료 심박이 있어야 잡힌다. 지금은 시작 로그만 있고 완료가 없으면 침묵이다.
      ⚠ 2026-09-23: ①②가 실제로 일을 했다 — 첫 회차(9/22 15:38) 로그에서 2·3단계(네이버 크롤)가 100% 실패
      중인 것이 드러나 둘 다 은퇴시켰다(분기→V55, 영업이익률→KIS 1단계). 배치는 이제 2단계, 82분→약 38분.
      그때도 배치는 예외 없이 '완료'였다:
      '배치는 도는데 특정 단계만 0건'을 보는 규칙은 아직 없다 — ③과 같이 다룰 것.
      그리고 값 자체(성장률 분포 -286,725%~240,600%)는 이 플래그와 별개로 남아 있다.
      ⚠ 별개 사안 — 성장률 값 자체가 채워진 날에도 쓸 수 없다. 과거 행 분포가
      -286,725% ~ 240,600% 이고(적자·소액 분모) 이 때문에 2026-09-21 에 Forward 지표를
      껐다. 배치를 고쳐도 그 분포 문제는 그대로다.
    recorded_on: 2026-09-22
    ref: AsyncCrawlerService.collectAllInOneAsync 4단계, FinancialDataScheduler 08:30/15:38

  - id: forecast-fallback-fixed-probabilities
    severity: info
    title: AI 예측 fallback 의 시나리오 확률·근거 문구가 상수다
    key: GeminiService
    body: >
      2026-09-22 예측상세 모달 검증에서 확인. AI 분석 실패 시 fallback 이 Bull 30% / Base 50% /
      Bear 20% 와 "외국인 매수 유입 시 상승 가능" 같은 근거 문구를 고정값으로 돌려준다.
      D+5 목표가는 지수 기반이 맞다(currentIndex x (1 +- 0.005 x day)) — 확률과 근거만 상수다.
      화면에 "AI 분석 일시 불가 — 현재 지수 기반 기계적 예측입니다" 와 "AI 분석 데이터 부족으로
      기본 예측을 제공합니다" 두 줄이 붙고 응답에 fallback:true 가 있어 **위장은 아니다**.
      다만 "지수 기반"이라는 표현은 목표가에만 해당하고 확률에는 해당하지 않는다.
      판단 사안 — fallback 에서 확률·근거를 빼고 기계적 가격 밴드만 보여줄지.
      severity 를 info 로 둔 것은 공시가 이미 붙어 있기 때문이다.
    recorded_on: 2026-09-22
    ref: GeminiService 예측 fallback, ForecastDetailModal.vue, SectionMarketMap.vue
```
