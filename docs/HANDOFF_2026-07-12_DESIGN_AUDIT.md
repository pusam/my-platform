# HANDOFF 2026-07-12 — 디자인/기능 전체점검 후속 작업

> 2026-07-12 디자인·기능 전체점검(프론트 UX 감사 + 백엔드 결함 감사)에서 **완료된 것**과
> **다음 세션이 이어할 것**. 완료 커밋: `ee2201a6`(기능·보안 7건, auto-commit 메시지에 묻힘),
> `9c45503e`(신호색 통일+허브 토큰화), `5a940311`(레거시 토큰화).

## 완료 (다시 하지 말 것)

1. **[보안] ExchangeRateService authkey 로그 유출 차단** — `maskAuthKey` 순수함수 + `ExchangeRateServiceTest`.
2. **등락색 통일** — TodayBriefingTab `.positive/.negative` → `var(--stock-up/down)` (한국 관례 빨강=상승).
3. **매매 신호색 통일** — `common.css`에 `--signal-strong-buy(#ef4444)~--signal-strong-sell(#3b82f6)` 신설.
   - **원칙(common.css 주석에 명문화)**: 매수/매도 '행동 라벨'(배지·등급·verdict)만 주식 관례색(매수=빨강/매도=파랑).
     점수·적중률·신선도·진입존(눌림🟢/과열🔴) 같은 "높을수록 좋음" 품질 스케일은 초록(--success) 유지. **이 구분을 깨지 말 것.**
   - StockConclusionCard: 강력매수 🟢→🔴, 매수 🔵→🟠, 손절/목표가·MFE/MAE 를 등락 방향색으로 정렬.
   - TodayBriefingTab 매수 등급 배지 초록/파랑 → 빨강 계열.
4. **표면 토큰화(값 보존 — 렌더 동일)** — `--surface-card(-soft)/--surface-solid/--surface-radius/--bg-hub-top/--surface-panel(-strong)/--text-on-accent/--bg-gradient-auth`. 허브 15파일 + 레거시 13파일 치환 완료.
   - **의도적 제외(치환하지 말 것)**: 일회성 장식 그라데이션(MarketTiming 무드 5종, 추천카드/게이지 투톤 등), FinanceManagement Chart.js JS 색, StockDetailDashboard SVG stroke.
   - VolumePowerGauge 만 유일하게 시각 변화 있음(독자 색 → 허브 표면 정렬, 의도됨).
5. NaN% 가드(StockDetailDashboard `formatChangeRate`), StockConclusionCard formatPrice ko-KR+null 방어,
   Login 생체등록 alert→toast, TodayBriefingTab 클릭 카드 3종 접근성(role/tabindex/enter), DashboardHeader 모바일 `top:77px` 매직넘버 제거.

## 다음 할 일 (우선순위순)

### A. 프론트 잔여 (감사에서 확인됐으나 미착수)

1. ~~[중간] 클릭 div 접근성 잔여~~ ✅ **완료(2026-07-12 후속 세션)** — rec-card ×5 에 `role="button" tabindex="0" @keydown.enter`,
   SectionJudgmentBoard 는 `<tr>`/`<th>` 라 role 덮어쓰기 없이 `tabindex="0" @keydown.enter` 만(테이블 시맨틱 보존).
   pick-card 는 StockTradingDashboardV2 에 CSS 잔재만 있고 템플릿 없음(MagicFormulaSmartTable 의 pick-card 는 별개) — 대상 아님.
2. ~~[중간·큼] 버튼/배지 스타일 파편화~~ ✅ **완료(공용 클래스 방식, 2026-07-12 후속)** —
   배지: `common.css` `.badge-unverified` 신설(ts-beta/ov-beta/tp-atr-badge, 렌더 동일).
   버튼: 고스트 패턴(흰 반투명+테두리+호버 밝힘) 공유 3종(ts-toggle/tool-btn/webauthn-btn)을
   `common.css` `.btn-ghost` 골격으로 통합 — 로컬은 크기·radius·강조 hover 만
   (⚠ 로컬 hover 는 `:hover:not(:disabled)` 형태로 써야 골격 hover 를 이긴다).
   tool-btn 표면 rgba 0.05/0.10 → 0.06/0.12 로 정규화(식별 불가 수준).
   **gnb-btn(네비 탭)·checklist-btn(블루 액센트 CTA)은 각자 1곳뿐인 고유 패턴이라 공용화 제외**
   (중복 없음 — BaseButton.vue 컴포넌트 도입은 실익 없어 보류 판단, 새 버튼 추가로 중복이 다시 생기면 재검토).
3. ~~[낮음] TodayBriefingTab `today-overnight` 클래스 공유~~ ✅ **완료** — 매크로 섹션 `.today-macro` 분리(스타일 동일 복사).
4. ~~[낮음·선택] Login 생체등록 `confirm()`~~ ✅ **완료(2026-07-12 후속, 사용자 위임)** —
   페이지 내 enroll 모달로 교체(Promise 대기로 confirm 과 동일한 차단형 양자택일 유지,
   오버레이 클릭 닫기 없음 — 명시적 선택만). '나중에'= dismissKey 저장(기존과 동일).
5. ~~[검토] StockDetailDashboard 점수 게이지류 색~~ ✅ **판단 확정(2026-07-12 후속, 사용자 위임)** —
   ai-score-box.high(점수 구간)·composite-badge.cb-strong(신호 매칭 개수)은 **품질 스케일 확정 → 초록 유지**
   (common.css 원칙 부합, 재분류 아님). 대신 그 안의 **score-badge(BUY/HOLD/관망 등 행동 라벨)가
   무채색이던 것을 신호색으로 정렬**(sb-strong-buy~sb-strong-sell, recBadgeClass/fundVerdictBadgeClass) —
   원칙("행동 라벨만 관례색")을 헤더에도 구현. RSI 과열→'관망' 표시는 중립색(getAdjustedVerdict 와 동일 규칙).

### B. 백엔드 잔여 (2026-07-12 감사 발견분)

1. ~~[하] `e.getMessage()` null 로깅~~ ✅ **완료** — CatalystRiskAlertService :87 `e.toString()`, :106 예외 객체 전달(스택 포함).
   debug 레벨 best-effort 경로(:95,:118)는 유지.
2. ~~[하] AutoTradingBotService 빈 catch 관측성~~ ✅ **완료** — :1750/:2259/:3200 텔레그램 발송 catch 에 `log.debug` 한 줄(동작 무변경).
   나머지 빈 catch(bus.subscribe/unsubscribe)는 정당 — 그대로 둠.
3. ~~[판단 필요] 악재경보 부분 발송 실패~~ ✅ **완료(사용자 승인 후, 2026-07-12 후속)** —
   채널별 dedup 키 분리: 시그널 = 레거시 `CATNEG_`(배포 전후 연속성) / 리스크 = `CATNEGR_` 신설.
   `claimAndSend` 로 채널별 독립 선점→발송, 실패 채널 선점만 반납(당일 재시도), 성공 채널 유지(스팸 방지).
   부수 개선: 시그널 발송 실패가 리스크 발송을 막지 않음(이전엔 예외로 sendRisk 미도달).
   재현 테스트(수정 전 3건 실패 확인) 포함 `CatalystRiskAlertServiceTest` 16건 green.

### C. 검증 방법 (모든 변경 후)

- 프론트: `cd frontend && npm test`(183개 기준) + `npm run build`
- 백엔드: `JAVA_HOME=C:/Users/kdhgl/.jdks/temurin-17.0.17 ./gradlew test` (java 가 PATH 에 없음)
- **auto-commit 자동화 주의**: 외부 자동화가 주기적으로 working tree 를 auto-commit+push 함.
  편집→테스트→커밋을 빠르게, 커밋 전 `git status` 로 선점 여부 확인 (ee2201a6 이 실제로 선점된 사례).

### 참고 — 감사에서 "이상 없음" 확정 (재감사 불필요)

- 최근 커밋 영역(KisTokenManager 통합, V48 nullable, 401 CAS, 악재 dedup INSERT 선점): 결함 없음.
- 자원 누수/토큰 원문 로깅/문자열 `==` 버그: 없음. TODO/FIXME: 백엔드 0건.
- console.log 잔재: 없음 (console.warn/error 는 전부 best-effort 실패 로깅 용도).
