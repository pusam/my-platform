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
  - id: kis-income-statement-dead
    severity: critical
    title: KIS 손익계산서·재무상태표 호출이 조용히 죽어 있다 — 매출/영업이익/순이익/자본총계 전멸
    key: FHKST66430300
    body: >
      2026-08-26 prod 실측(일별 행 434건, report_date=2026-08-25):
      per/pbr/roe 는 434/434 채워지는데(재무비율 FHKST66430200 정상)
      revenue/operating_profit/net_income/total_equity 는 0/434 다
      (손익계산서 FHKST66430300 · 재무상태표 FHKST66430400 전멸).
      파급 ① earnings 가 네이버 크롤 분기 행에만 의존 → 431종목 중 채점 가능 ~90
      ② composite scoreValueStability 가 영업이익·자본총계를 최근 10행에서 못 찾아 가치 축 일부가 조용히 결손
      ③ V55 분기 원본 적재가 이 응답에 물려 있어 빈 테이블 — 전환 플래그를 켤 수 없다.
      원인 미확정. 실패 경로는 이미 WARN 으로 찍힌다 —
      docker compose logs backend | grep 손익계산서 로 msg1 을 읽고 고칠 것. 추측 수정 금지.
      ⚠ 컨테이너 재시작 후엔 다음 배치(08:30/15:38)까지 로그가 비어 있다(그게 '정상'이라는 뜻 아님).
    recorded_on: 2026-08-26
    ref: VERIFICATION_BACKLOG "R1 진단 정정 (2026-08-26 오후)"

  - id: financial-universe-434
    severity: warning
    title: 재무 수집 유니버스가 434종목뿐 — 다음 배치부터 확장되며 소요시간 6배
    key: R5
    body: >
      stock_financial_data 에 434종목만 있다(KOSPI+KOSDAQ ~2,700). 유니버스 자기참조가 원인이고
      2026-08-26 합집합 수정으로 다음 배치부터 stock_master 활성 종목이 들어온다.
      ⚠ collectAllStocksFinancialData 는 종목당 300ms sleep 이라 434 → ~2,700 이면 sleep 만 13분+,
      전체 소요가 6배로 늘어난다. 08:30 배치가 다른 일정과 겹치지 않는지 첫 실행 소요시간을 확인할 것.
      늘어난 유니버스가 KIS rate limit 을 건드리는지도 같이 볼 것.
    recorded_on: 2026-08-26
    ref: StockFinancialDataService.resolveCollectionUniverse

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
