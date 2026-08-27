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
  - id: quarterly-cumulative-misdetect-fixed
    severity: critical
    title: 누적(YTD) 오판 96% — TTM 2배 부풀림. 수정 배포됨, 재수집 후 검증 필요
    key: detectCumulative
    body: >
      2026-08-27 실측 — stock_quarterly_financial 의 2,523/2,618종목(96%)이 cumulative=0 으로
      저장됐는데 실제로는 전부 누적(YTD)이었다. 삼성전자 202503=7,914 / 202506=15,370 /
      202509=23,976 / 202512=33,360 / 202603=13,387(FY 리셋) 로 명백한 누적이다.
      원인은 판정 휴리스틱이 최신 3개만 보고 단조증가를 확인한 것 — 회계연도 경계가 그 안에
      들어오면 반드시 깨진다. 8월은 FY 리셋 직후라 구조적으로 실패하는 시기다.
      파급이 두 갈래다. ① 서프라이즈 변화율 오염 — Q1 vs 반기누적을 비교해 임계를 5배로 올려도
      건수가 안 줄었다(20%→782건, 100%→559건). ② TTM 합산 오염 — 누적 4개를 그냥 더해
      삼성전자 101,262 vs 진짜 48,527, 즉 2.09배 부풀었다. TTM 은 composite 의
      scoreValueStability(영업이익·자본총계)와 저평가/성장 트랙이 소비한다.
      ⚠ 이 오염은 2026-08-27 tr_id 정정 이후 처음 DB 에 들어갔다(그 전엔 전부 NULL).
      즉 오늘 08:30 배치가 적재한 값이 오염돼 있다 — 다음 배치(15:38)에서 덮어써진다.
      수정 = 판정을 이력 전체의 인접쌍으로 하고(증가비율 + 배율 중앙값 두 판별자),
      TTM 은 개별 분기 환산 후 최근 4분기 합. 삼성전자 실측으로 회귀 테스트 고정.
      확인 방법 — 15:38 배치 후 stock_quarterly_financial 의 cumulative=1 비율이 다수가 되는지,
      [손익계산서 TTM] 로그의 매출액이 절반 수준으로 내려오는지.
    recorded_on: 2026-08-27
    ref: QuarterlyFinancials.detectCumulative/ttmSum, QuarterlyFinancialsTest RealSamsungData

  - id: financial-unit-100x
    severity: warning
    title: 재무 금액이 100배 작게 저장된다 — 원본이 이미 억원인데 /100 을 한다
    key: toEokWon
    body: >
      실측 대조 — SK하이닉스 201912 연간 매출이 DB 에 2,699.07 인데 실제는 26.99조(269,907억)다.
      삼성전자 202512 누적도 33,360 vs 실제 약 333조. 정확히 100배 작다.
      수집기가 "백만원 → 억원"이라며 /100 을 하는데 KIS 원본이 이미 억원 단위로 보인다.
      변화율(%)에는 영향이 없어 서프라이즈 판정은 무관하지만, 저장된 절대값과 "매출 N억" 표시,
      그리고 절대액 비교를 쓰는 곳이 전부 틀린다. ⚠ marketCap·totalEquity 는 다른 API 에서 오므로
      같은 단위인지 별도 확인 필요 — 한꺼번에 /100 을 지우면 안 된다.
      확인 방법 — [손익계산서 RAW] 로그의 원본 문자열과 실제 공시 금액을 종목 2~3개로 대조.
    recorded_on: 2026-08-27
    ref: StockFinancialDataCollector.toEokWon, 단위변환 주석

  - id: earnings-quarterly-switch-pending
    severity: warning
    title: 분기 원본 수집 정상화 완료 — earnings 전환 플래그만 OFF 로 남아 있다
    key: R1
    body: >
      2026-08-27 08:30 배치에서 KIS 재무 3종이 정상 복구됐다(tr_id 정정 배포 08:01).
      실측: 유니버스 2,656종목 · 스키마 경고 0건 · 손익계산서 TTM 에 실제 금액 유입 ·
      stock_quarterly_financial 2,618종목 적재 · coverage.distinctStocks 2,289 ·
      maxPeriodEnd 2026-06-30. 즉 켤 조건(≥2000)은 충족됐다.
      ⚠ 다만 2026-08-27 오후 누적 오판이 드러나 켜는 조건이 하나 늘었다 — 커버리지뿐 아니라
      '변별력'도 봐야 한다. 실측 quarterlyScoring 916/2,289 = 40% 였고 임계를 5배로 올려도
      안 줄었는데(누적 오판이 변화율을 오염시켰기 때문), 재수집 후 스윕을 다시 봐야 한다.
      켜기 전 GET /api/diagnostics/earnings-source?compare=true 로 레거시/분기 건수를 비교하고,
      켠 날짜를 SCHEDULE_DECISIONS 의 earnings-quarterly-switch 행에 기록할 것 —
      composite 총점·validCount·후보 수가 동시에 움직이므로 그 날이 측정 표본의 경계가 된다.
      켜는 법: .env 에 RECOMMENDATION_EARNINGS_QUARTERLY_SOURCE=true 후 docker compose up -d backend
      (restart 아님 — recreate 필요).
    recorded_on: 2026-08-27
    ref: docker-compose.yml backend environment, docs/SCHEDULE_DECISIONS.md

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
