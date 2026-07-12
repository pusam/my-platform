# HANDOFF 2026-07-13 — 다음 작업 후보 (2026-07-12 세션 정리)

> 2026-07-12 디자인/기능 감사 핸드오프(HANDOFF_2026-07-12_DESIGN_AUDIT.md)는 **전 항목 종결**
> (A1~A5, B1~B3 + diff 재검토 버그픽스 `218d54ad`). 이 문서는 그 다음에 할 만한 것들.
> 각 항목에 착수 조건을 명시했으니 조건 안 되는 건 건너뛸 것.

## 검증 방법 (공통)

- 프론트: `cd frontend && npm test`(183개 기준) + `npm run build`
- 백엔드: `JAVA_HOME=C:/Users/kdhgl/.jdks/temurin-17.0.17 ./gradlew test` (java 가 PATH 에 없음)
- python-backend: `cd python-backend && pytest`
- **auto-commit 자동화 주의**: 커밋 전 `git status` 로 선점 여부 확인.

## 우선순위순 후보

### 1. [중] python-backend regime 도메인 테스트 도입 — 착수 조건 없음, 바로 가능

- 현재 pytest 는 `tests/test_indicators.py`(차트기법 순수함수)뿐. **regime 분류(v1: KOSPI 종가
  vs MA60 + MA20 5거래일 슬로프 → BULL/BEAR/SIDEWAYS)는 테스트 0건** — 봇/추천 가중치가
  소비하는 신호인데 회귀 안전망이 없다.
- 방법: `regime_service.py` 의 분류 로직을 순수함수로 분리(이미 분리돼 있으면 그대로) 후
  경계값 테스트(MA60 상/하, 슬로프 ±, 경계 동률). **국면 규칙 자체는 변경 금지**(CLAUDE.md §4c —
  검증 데이터 쌓이기 전 임의 변경 금지). 테스트만 씌운다.
- 시작점: `python-backend/app/.../regime_service.py`, `app/utils/index_source.py`
  (KOSPI 소스는 Java `/api/market/index/kospi-daily` 경유 — pykrx 지수로 되돌리지 말 것, 깨져 있음).

### 2. [중] 봇 미체결 잔여분 능동 취소 (B2-A Phase 2, `order-rvsecncl`) — **착수 조건: 모의계좌 검증 가능할 때만**

- 현재: 매도 부분/미체결 확정 시 포지션 유지 + 다음 사이클 재시도(수동 취소 없음). Phase 2 는
  잔여 주문을 KIS `order-rvsecncl` 로 능동 취소 후 재주문.
- **주문변경 API 라 모의계좌 검증 필수**(CLAUDE.md §4d) — 코드만 먼저 짜두는 것도 가능하지만
  검증 없이 REAL 경로에 붙이지 말 것. VIRTUAL/모의 플로우로 가드.
- 시작점: `RealTradeService.confirmFill`/`resolveFill`, `AutoTradingBotService` 매도 3경로,
  TR_ID 규격은 KIS 문서 확인.

### 3. [중·큼] Gemini 배치 프롬프트 — 무료 티어 RPM 실질 감축 (착수 조건 없음, 설계 신중)

- 현재 rate 제한(전역 직렬화 4.5초)으로 429 는 방어 중이지만, 처리량 자체가 낮다(≈13 RPM).
  N종목 1콜 배치 프롬프트가 무료 티어 최선책(CLAUDE.md §4b 백로그 명시).
- 재료 분류(`StockCatalystService`)부터: 모닝브리핑 후행 워밍(상한 5종목)을 1콜로 묶는 게
  가장 좁고 안전한 시작. 응답 파싱 실패 시 **캐시 안 함**(기존 원칙 — 실패를 NONE 으로 위장 금지).
- 같이 하면 좋은 것: 소비자 우선순위(재료 > AI전략) — `GeminiService.RateLimiter` 에
  우선순위 큐. ⚠ 기존 RateLimiter 의 synchronized 슬롯 예약 방식을 깨지 말 것(비원자 방식 회귀 금지).

### 4. [소] `inquireDailyCcld` 응답 필드 1회 확인 — **착수 조건: 실전 첫 매도 발생 후**

- `output1`/`odno`/`tot_ccld_qty`·TR_ID(TTTC0081R)는 규격 기준으로 작성됨 — 실전 매도 로그로
  1회 대조. 틀려도 null→UNKNOWN→현행 제거라 안전하지만, 확인 전까지 부분체결 보호가 무효일 수 있음.
- 확인만 하면 되는 작업. 로그 위치: `RealTradeService.confirmFill`.

### 5. [소] pykrx ticker_list 복구 (P3-4 잔여) — 착수 조건 없음

- reconstructed 백테스트용 ticker_list 가 pykrx KRX 포맷 변경 여파로 잔여 이슈.
  regime/sector 는 이미 Java 소스로 우회 완료 — ticker_list 만 남음.
- 시작점: `python-backend/app/utils/index_source.py` 패턴 재사용(Java 쪽에 종목 리스트
  엔드포인트가 이미 있는지 먼저 확인).

### 6. [관찰] 신선한 눈 전체 감사 — 오늘 안 본 영역 한정

- 2026-07-12 에 프론트 UX + 백엔드 결함 감사를 2회 돌렸으므로 같은 영역 재감사는 수확 체감.
  하려면 **오늘 안 본 영역**: python-backend 전체(regime/차트기법 외 유틸), 스케줄러 크론들의
  예외 경로, `StockDetailService`(4,700줄급 — 오늘 verdict enum 만 확인), 레거시 뷰(BoardPage/
  FileManager/FinanceManagement 등 비주식 도메인).

## 조건 대기 (건드리지 말 것 — 트리거 오면 그때)

- **수급 캡(10) 재조정**: `SignalWeeklyReportService` 주간 리포트의 캡 전/후 성과 비교 데이터
  대기 (SB 표본 8행/1종목으로 아직 작음). 캡 확대(5트랙/보드)·무캡 복귀 금지.
- **차트 타이밍 승격**: P2-12 백테스트 부진(31%·역상관)으로 관찰용 확정 — 재백테스트로
  결과가 뒤집히기 전 금지.
- **BaseButton.vue 도입**: 남은 버튼(gnb-btn/checklist-btn)이 각 1곳뿐인 고유 패턴이라 실익
  없음 판단(2026-07-12). 새 화면/버튼 추가로 중복이 다시 생기면 재검토.
- **멀티 인스턴스 fail-closed 봇 락**: 단일 인스턴스 전제가 깨질 때만.

## 세션 노트 (2026-07-12 마감 상태)

- 마지막 커밋 `218d54ad` — verdict 배지 매핑 실값 정합 + 악재경보 이력 채널 키 + alert→toast.
- 눈 확인 권장(사용자): 종목 상세 헤더 점수배지 신호색(A5), 모바일 로그인 지문등록 모달(A4).
- 테스트 기준선: 프론트 183 / 백엔드 전체 green / python pytest(지표만).
