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

  - id: earnings-flag-still-off
    severity: warning
    title: earnings 분기소스 플래그는 여전히 OFF — 켜는 판단만 남았다
    key: R1
    body: >
      2026-08-28 TURNAROUND 규칙 재설계 완료(후보안 (나) 연속 적자 조건). 실측 209건 중
      한 분기 삐끗 111건이 최고점 20점을 받던 것을 제거했다. 남은 것은 사람 판단 하나 —
      recommendation.earnings.quarterly-source 를 true 로 켜는 것.
      켜면 composite 총점·validCount·후보 수가 동시에 움직이므로 켠 날짜가 측정 표본의 경계다.
      ⚠ 임계(±20%)는 일부러 안 건드렸다. 어느 값이 옳은지는 forward 성과 측정이 필요하고,
      근거 없이 올리면 ±20% 처럼 출처 없는 상수가 하나 더 생긴다. 켠 뒤 표본이 쌓이면 그때 잰다.
      켜는 법 — .env 에 RECOMMENDATION_EARNINGS_QUARTERLY_SOURCE=true 후 docker compose up -d backend
      (restart 아님, recreate). 켜기 전 GET /api/diagnostics/earnings-source?compare=true 로
      turnaroundBreakdown 과 sweep 을 다시 확인할 것.
    recorded_on: 2026-08-28
    ref: EarningSurpriseService.classifyQuarterly, docs/SCHEDULE_DECISIONS.md turnaround-rule-redesign

  - id: financial-unit-fix-verify
    severity: warning
    title: 재무 금액 100배 보정 배포됨 — 다음 배치에서 PBR·BPS 정상화 확인 필요
    key: V56
    body: >
      2026-08-28 확정 — KIS 손익계산서·재무상태표 원본이 이미 억원인데 수집기가 "백만원 → 억원"이라며
      /100 을 해서 모든 금액이 100배 작게 저장됐다. 실측: 005930 자본총계/시총 = 0.00377(PBR 265),
      000660 은 0.0021(PBR 477) — 어떤 회사에도 불가능한 값이다.
      ⚠ PBR 일관성 가드(PBR ≈ PER × ROE / 100)가 그 PBR 을 덮어써서 화면엔 정상으로 보였다.
      **가드가 단위 버그를 가리고 있었다** — 그래서 몇 달간 안 드러났다.
      손익계산서·재무상태표를 **함께** 고쳤다(ROE = 순이익/자본총계 라 둘 다 100배 작을 땐 비율이
      우연히 맞았고, 한쪽만 고치면 ROE 가 100배 틀어진다). V56 이 기존 행도 ×100 보정한다 —
      안 하면 최근 10행 합성이 옛 행과 새 행을 섞어 더 나빠진다.
      확인 방법 — 다음 배치 후 ① 종목상세 BPS·PBR 이 상식 범위인지(삼성전자 PBR 2~3 대)
      ② [PBR 보정] 경고 로그가 사라졌는지(가드가 더는 발동하지 않아야 한다)
      ③ 관제실 이상 점검의 financial-unit-mismatch 가 사라졌는지.
    recorded_on: 2026-08-28
    ref: V56__fix_financial_amount_unit_100x.sql, StockFinancialDataCollector 단위 주석

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
