# 프론트 IA

> 2026-11-11 에 옛 `CLAUDE.md` 에서 글자 그대로 옮긴 경위 원문이다. **지킬 규칙은 `CLAUDE.md` '프론트 IA' 절 의 규칙 줄에 있다** — 이 문서는 '왜'를 위한 것이다.
> 번호(예: `§4c.13`)는 옛 `CLAUDE.md` 의 절과 그 절 안 항목 순서다(하위 항목은 `§7.8-2`). 문단 안의 '위·아래 항목'은 이 순서를 가리킨다 — 전체 순서표는 `docs/rules/README.md`.
> 번호 제목 바로 아래의 '정정(날짜): …' 줄은 옮긴 뒤 붙인 것이다(문단은 손대지 않았다).
> 사실이 바뀌면 문단을 고치거나 정정 줄을 붙이고, `CLAUDE.md` 의 같은 번호 규칙 줄도 같이 고친다. `AGENTS.md` 는 `CLAUDE.md` 의 사본이고, 이 문서는 두 쪽이 같이 읽는다.

#### §ia.2-1

  - **시간대신호(장전/장후)·실시간수급(장중)·관심종목 = '오늘' 탭(2026-07-01, 발굴 슬림화 A 1단계)**: hub(StockTradingDashboardV2) 데이터를 **Vue 슬롯**(`#phase-signals`/`#watchlist`)으로 TodayBriefingTab에 주입(슬롯=hub scope라 데이터·CSS 유지, props/로딩 무변경). **발굴 목록은 5트랙 리스트 主로 슬림화**(차트신호 종목·관심종목 기본 접힘). 발굴↔오늘 위젯 되섞지 말 것. **차트신호 3 surface = 3 독립 엔진(중복 아님, P2-15 확정)**: ① 발굴목록 **'📐 차트 패턴'**=Java `ChartPatternService`(기하학 패턴, 유일출처) · ②③ **'🪝 차트 타이밍'**(오늘 관찰 + 종합판단 컬럼)=python `compute_timing`(같은소스, 백테스트 31%) · 🎯종합=`compositeRanking`(composite 5/5, 종합판단과 다른 엔진). **삭제·통합 금지**('패턴'=Java/'타이밍'=python 네이밍으로 구분만). 🎯종합은 종합판단 상위호환 아님(composite 손실이라 은퇴 금지).

#### §ia.4

- **차트 기법 통합 시 발굴/매수후보 스코어러는 항상 분리한다(2026-06-29).** 추세추종 기법은 성격이 다른 두 신호로 쪼개진다: ① 섹터 상대강도='덜 빠지는 섹터' = **발굴 탭 상단 배지**(유니버스 필터), ② 정배열+눌림목 진입 타이밍(momentum 아님, 추세 안의 mean-reversion, momentum 과 objective 정반대) = **'오늘' 탭 '🪝 차트 타이밍 관찰' 별도 섹션(매수후보 아님·접기 기본·점수 미표시)**. **기존 momentum 스코어러(`RecommendationService`/`getTop5`)에 욱여넣지 말고 별도 모듈**(python `app/indicators/*` + Java `ChartPatternClient`/`ChartSignalRanker`/`ChartSignalController`)로 둘 것. 타이밍은 momentum 매수후보를 **대체하지 않는 별도 관찰 리스트**. **P2-12 백테스트 결과 승격불가(2026-06-30: 적중률 31%·점수 역상관)** → 관찰용 유지, 실거래/봇/매수후보 승격 금지(`unverified=true`). 참고: momentum(`getTop5`)은 이미 '오늘' 탭 전용이라 발굴↔매매 공유 잔재 없음.

#### §ia.5

- **발굴 = 종합판단 중심 축소(2026-07-01)**: 노출 서브탭은 **🧭종합판단 + 백테스트뿐**(`discoverSubTabs`). **목록 5트랙·🎯종합·AI전략·스크리너·퀀트(TA)는 숨김** — 삭제 아님, **코드(렌더 블록/임포트/로더)·딥링크(`?sub=`) 보존**(복구: `discoverListVisible=true` + `resolveInitialDiscoverGroup` list 분기 되살리기 + `discoverSubTabs` 항목 추가). **2026-10-07 💎저평가 목록만 다시 보인다**(사용자 "주식 가치에 비해 저가인 종목도 볼 수 있는 거지?") — `discoverListVisible=true` + `visibleDiscoverListKeys: ['value']`(목록 줄엔 💎저평가 하나, 나머지 4트랙은 그대로 숨김 — 되살리려면 그 배열에 키를 더한다), 딥링크 `?tab=discover&sub=value`, 목록 화면엔 갱신 시각 막대 없음(60초마다 다시 읽지 않는다), 머리에 무엇을 재는지·**성과 검증 전**이라는 한 줄(`utils/marketDataLabels.VALUE_LIST_BASIS`), 서버가 계산 실패(`dataAvailable=false`)라고 하면 '수집 중'이 아니라 '불러오지 못했습니다'(§4c). 같은 날 그 목록의 입력·순위를 고쳤다(5차 ㉓ 부채비율·㉔ 순위 정비). ⚠ 장기 성과 기록은 없다(사용자가 고르지 않음) — 가치 신호는 D+3 로 검증되지 않는다. 회귀 프론트 `StockTradingDashboardV2.valueList`. 목록 5트랙 종목은 **종합판단 union(발굴 트랙 포함) 토글에 이미 포함**돼 보드에서 비교 가능(빈-보드 폴백도 '목록 탭'→'발굴 트랙 포함' 토글로 유도). `discoverGroup` 기본 'deep'(💎저평가 딥링크·탭만 'list'). 이전 2단(목록+심화) 구조·9위젯 섹션 코드는 남아있고 게이팅으로 비노출. **🎯종합 vs 🧭종합판단**(둘 다 momentum, 종합=빠른목록/종합판단=3계층 비교표)은 여전히 코드 공존 — 🎯종합만 nav 에서 숨긴 것(은퇴 아님). 5트랙 백엔드는 모두 별도 엔드포인트·산식·성격(코드 유지):

#### §ia.6

- **허브 60초 갱신은 보이는 탭의 데이터만(2026-10-07, `StockTradingDashboardV2._refreshAll`)**: 오늘 = 시장 한 줄(`loadMarketLine`, 시장 상태 1콜) · 시장 = 시장 지도·수급 패널 · 발굴 = 종합판단 보드만. 예전엔 발굴 탭에서도 시장 탭 데이터(지도 5콜·수급 3콜)를 60초마다 다시 읽었고, 반대로 **오늘 탭의 KOSPI·KOSDAQ 줄은 한 번도 다시 읽지 않아 페이지를 연 시점 값이 그대로 보였다**(기준 시각 표시도 없이). 다른 탭에서 돌아오면 그 탭 데이터를 바로 한 번 읽는다(watch `activeGnbTab`). 시장 지도는 화면에 쓰는 나스닥 선물만 부른다 — 선행 섹터·USD/KRW 는 시장지표 탭이 없어진 뒤 받아서 버리기만 했다(`StockTradingDashboardV2.ia.test.js`).
