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
    severity: info
    title: 대조군 유니버스가 시그널 유니버스와 비대칭 (edge 과대 방향)
    key: R2
    body: >
      대조군 추출 모집단이 시그널 후보 모집단과 달라 edge 가 과대 추정되는 방향으로 치우칠 수 있다.
      n≥30 도달 후 첫 판정 전에 유니버스 정의를 맞추거나, 못 맞추면 판정문에 비대칭을 명시할 것.
    recorded_on: 2026-08-21
    ref: VERIFICATION_BACKLOG "AUDIT 2026-08-21" R2
```
