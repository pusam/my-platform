# 판정 관제실 (Control Room)

> **한 줄**: 판정 캘린더·KPI·FLAGGED 를 한 화면에 모으고, AI 크루 3명이 오퍼레이터 지시 하나에
> 대해 서로 대화해 초안→검토→반영→결론을 내는 **읽기 전용 운영 콘솔**.
>
> **작성**: 2026-08-24. 산식·봇·게이트·가격 경로 무변경 — 조회 + LLM 레이어일 뿐이다.

---

## 0. 왜 이 화면이 필요했나

`docs/SCHEDULE_DECISIONS.md` 에 "데이터 N주 쌓이면 판정" 안건이 8건 쌓였는데 **판정 기록이 0건**이었다.
표는 있는데 아무도 안 보니까 기한이 지나도 아무 일이 일어나지 않았다. 관제실은 그 표를 **화면으로
끌어올려 기한 초과를 눈에 띄게** 만들고, 판정에 필요한 맥락(게이트 상태·플래그·불변식)을 한 자리에
모은 다음, 그걸 근거로 크루가 "지금 갈 수 있냐"를 정리하게 한다.

**판정 자체는 사람이 한다.** 크루는 정리와 반박까지만이다.

---

## 1. 절대 원칙 — 읽기 전용

크루는 **아무것도 실행하지 못한다.** 3중으로 막혀 있다.

| 층 | 방어 |
|---|---|
| ① 툴 | `CrewLlmClient` 는 `tools` 를 아예 넘기지 않는다. DB·파일·봇·주문에 닿을 **경로 자체가 없다** |
| ② 프롬프트 | 시스템 프롬프트에 "너는 읽기 전용이다 — 결론은 제안이고 실행은 사람이 한다" 명시 |
| ③ 쓰기 범위 | 이 기능이 하는 DB 쓰기는 **`crew_session` / `crew_message` 대화 기록뿐**이다 |

화면의 "액션: A \| B" 버튼도 **새 지시를 보내는 것뿐**이고, 버튼 옆에 그 사실이 상시 표기된다.

> ⚠ **확장할 때**: 크루에게 툴을 주고 싶어지는 순간이 온다(예: "SCHEDULE_DECISIONS.md 를 직접
> 고치게 하자"). 하지 말 것. 이 화면의 가치는 "틀려도 아무 일이 안 일어난다"에서 나온다.
> 문서 수정이 필요하면 크루가 **초안 텍스트를 뽑고 사람이 붙여넣는다**.

---

## 2. 아키텍처

```
docs/SCHEDULE_DECISIONS.md ─┐
docs/CONTROL_ROOM_FLAGS.md ─┼─→ 파서 3종(순수) ─┐
CLAUDE.md (불변식 소제목)   ─┘                   │
                                                 ├─→ ControlRoomSnapshotService ─→ GET /snapshot ─→ Vue
JudgmentBoardService        ─┐                   │        (30초 메모리 캐시)
TradingSafetyService        ─┤                   │
DailyLossBreakerService     ─┼─→ KPI 5종 ────────┘
VolatilityRegimeService     ─┤                   │
bot.nxt-* 설정 플래그        ─┘                   │
SignalWeeklyAccuracyRepo    ──→ 주간 피드백 실행 여부
                                                 │
                                                 ↓
                                    CrewContextBuilder (8KB 상한)
                                                 ↓
                        CrewOrchestrationService — 5턴 순차, 단일 스레드
                                                 ↓
                                    CrewLlmClient → Anthropic Messages API
                                                 ↓
                                    crew_message (턴별 저장) ─→ GET /crew/sessions/{id} 폴링
```

**패키지**: `com.myplatform.backend.controlroom` — 기존 서비스 패키지와 분리했다. "관제실은 조회
레이어"라는 경계를 디렉터리로도 유지하기 위해서다. 이 패키지가 다른 도메인 코드를 **호출**하는 건
정상이고, 다른 도메인이 이 패키지를 호출하기 시작하면 경계가 무너진 것이다.

---

## 3. 크루 3명과 5턴

| 에이전트 | 역할 | 특이사항 |
|---|---|---|
| **에렌** (`EREN`) | 총괄 — 지시를 분배하고 마지막에 결론 | 1턴·5턴 담당 |
| **SCOUT** | 분석 — 순서·날짜·파일명이 든 구체적 초안 | 2턴·4턴 담당 |
| **FIREWALL** | 검증 — 불변식·플래그에 대조, 승인/조건부/반려 | 3턴. **숫자를 새로 계산하지 않는다** |

```
1 ROUTING  에렌 → "SCOUT: … / FIREWALL: …" 분배 (결론 금지)
2 DRAFT    SCOUT → 초안, 마지막 줄 "초안 끝, FIREWALL 검토 바람."
3 REVIEW   FIREWALL → 첫 줄 [승인]/[조건부]/[반려] + 보완 최대 3개
4 REVISE   SCOUT → 바뀐 부분만, 마지막 줄 "반영 완료."
5 CLOSING  에렌 → "결론:" 3문장 + "액션: A | B"
```

**FIREWALL 이 숫자를 안 만드는 이유**: 계약 대비 %·게이트 통과 수 같은 계산은 백엔드가 이미 끝내서
컨텍스트에 넣는다. LLM 이 재계산하면 화면 숫자와 크루 숫자가 갈라지고, 어느 쪽이 맞는지 아무도 모르게 된다.

**5턴 고정이다.** 자동 루프도 자동 재시도도 없다. 어느 턴이든 실패하면 세션은 `FAILED` + 사유로
끝나고, 재시도 여부는 사람이 정한다. 조용한 재시도는 LLM 비용이 새는 가장 흔한 경로다.

---

## 4. 데이터 소스

### 4-1. 판정 캘린더 — `docs/SCHEDULE_DECISIONS.md` 의 YAML 블록만

**헤딩은 파싱하지 않는다.** `## 2026-07-22 경`, `## 2026-08 중`, `## 수시 (선택)` 처럼 근사 표현이
섞여 있어 날짜로 쓸 수 없다. 캘린더에 올릴 항목은 사람이 파일 끝 **"관제실 기계 판독 블록"** 에
확정 날짜로 적는다. 스키마와 필드 설명은 그 블록 위에 표로 적혀 있다.

핵심 필드 2개:
- **`trigger`** — 조건 트리거. 있으면 `due` 는 판정일이 아니라 **확인일**이고, 화면은 황색 +
  `확인 (조건: …)` 으로 판정일(보라)과 색까지 구분한다. 대조군 판정(n≥30)처럼 "날짜가 근거가 아닌"
  안건이 판정한 것처럼 보이는 걸 막는다.
- **`kind: milestone`** — 판정이 아닌 이정표(NXT 개시일 등). 로스터·미판정 집계에서 빠지고
  캘린더 핀으로만 남으며, 라벨에 "판정 아님"이 붙는다.

**미판정 건수**는 같은 파일의 **"판정 기록" 표**에서 판정일이 미기입인 행 수로 센다(YAML 이 아니다).
표에는 있는데 YAML 블록에 없는 안건은 캘린더에서 사라지므로 FLAGGED **"미등록 판정 N건"** 으로 뜬다.
→ 실제로 이 매칭이 최초 작성 때 표기 드리프트("VKOSPI 게이트 승격" vs "VKOSPI **변동성** 게이트 승격")를
잡아냈다. **표와 블록의 `title` 은 같아야 한다.**

`OVERDUE` 정의 = `due < 오늘 AND status ∈ {pending, deferred}`.

### 4-2. FLAGGED — `docs/CONTROL_ROOM_FLAGS.md`

**사람이 손으로 관리한다.** 자동 감지 소스가 아니다. 감사·리뷰에서 나온 항목을 적고 해소되면 지운다.

> ⚠ 이 파일의 가장 큰 실패 모드는 **해소된 항목을 안 지우는 것**이다. 최초 작성 시 목업이 들고 있던
> 5건 중 3건이 이미 해소 상태였다(`pattern-detection.enabled` 배선 완료 / CLAUDE.md 모순 해소 /
> V52 문서 등록 완료). 낡은 플래그는 첫 화면을 거짓말로 만들고, FIREWALL 이 그걸 근거로 반려한다.
> 화면이 `recorded_on` 경과일을 보여주고 30일 넘으면 강조하는 이유가 이것이다.

**"판정이 밀렸다"류는 여기 적지 않는다.** 미판정·OVERDUE 는 캘린더에서 결정적으로 계산된다.
손으로 중복 기록하면 두 값이 어긋난다.

여기에 더해 시스템이 유도하는 항목 2종이 **"자동 감지"** 배지로 함께 뜬다 — `파싱 오류: <id>` 와
`미등록 판정 N건`. 사람이 지워야 할 것과 코드가 다시 만들 것을 구분하기 위한 배지다.

### 4-3. 불변식 — `CLAUDE.md` 불변식 섹션 소제목

FIREWALL 이 초안을 대조할 기준. 하드코딩하면 CLAUDE.md 가 바뀔 때 조용히 어긋나므로 `###` 소제목만
파싱한다(본문은 컨텍스트 예산을 잡아먹어 제외). 섹션을 못 찾으면 빈 목록이 아니라 `dataAvailable=false` —
크루에게 "불변식 없음"을 주면 FIREWALL 이 아무 제약 없이 승인한다.

### 4-4. KPI 5종

| KPI | 소스 | 주의 |
|---|---|---|
| 종합판단 후보 | `JudgmentBoardService.getBoard("momentum")` | 등급 컷 75/55 는 **표시용 복제**. 산식 쪽 값 바꾸면 `ControlRoomSnapshotService` 상수도 같이 고칠 것 |
| 봇 게이트 | 킬스위치 · 일일손실 · VKOSPI · NXT 라우팅 · NXT 청산 | `gates()` 메서드의 목록이 **게이트의 정의**다. 전부 설정/DB 읽기 — KIS 호출 없음 |
| 일일손실 서킷 | `bot_config` + `sumRealizedPnlBetween` | **원(KRW) 단위.** 자산 대비 -3%/-1.5% 킬스위치는 **별개 장치**라 섞지 말 것 |
| VKOSPI 레짐 | `VolatilityRegimeService` | 목업의 "streak Nd" 는 소스가 없어 **표시하지 않는다**(§4c) |
| 미판정 | `SCHEDULE_DECISIONS.md` 판정 기록 표 | 표를 못 찾으면 0 이 아니라 "데이터 없음" |

**당일 실현손익**은 브레이커가 쓰는 것과 **같은 리포지토리 메서드·같은 인자**로 읽는다(계산 중복이
아니라 동일 쿼리 재사용). 봇 코드는 건드리지 않는다는 원칙 때문에 `DailyLossBreakerService` 에
public getter 를 추가하지 않았다.

### 4-5. 주간 피드백 (달력 녹색)

판정이 아니라 크론(`SignalWeeklyReportService`, 일 18:00)에서 유도한다. **지난 일요일은 실제 리포트
존재 여부**(`signal_weekly_accuracy.generated_at`)로 실행/미실행을 가른다 — 안 돌았는데 초록 점을
찍으면 "피드백이 돌고 있다"는 거짓 안심을 준다. 조회 창(최근 12주) 밖이라 알 수 없으면 `UNKNOWN`
("실행 여부 불명")이며, `MISSED`("미실행")와 **문구를 분리**한다.

---

## 5. 엔드포인트

전부 **ADMIN 전용**. `/api/control-room/**` → `hasRole("ADMIN")`.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/control-room/snapshot?month=YYYY-MM` | KPI + 캘린더 + FLAGGED + 불변식. 계산은 전부 여기서 끝난다 |
| POST | `/api/control-room/crew/sessions` | 지시 1건 → 5턴 백그라운드 실행. 즉시 세션 id 반환 |
| GET | `/api/control-room/crew/sessions/{id}` | 폴링(1.5초). status + 저장된 턴 |
| GET | `/api/control-room/crew/sessions` | 최근 20건 |

**거부 응답을 일반 오류와 섞지 말 것** — `409`=동시 실행 중, `429`=일일 상한 도달. 운영자가 대응을
달리해야 하므로 화면도 문구를 분리한다.

> ⚠ **실제 권한 게이트는 `SecurityConfig` 의 URL 규칙**이다. 이 코드베이스엔 `@EnableMethodSecurity`
> 가 없어서 `@PreAuthorize` 가 **전부 무효**다(관제실뿐 아니라 `PaperTradingController` 봇 제어 등
> 기존 컨트롤러도 마찬가지). 메서드 보안을 켜면 잠자던 어노테이션 수십 개가 한꺼번에 활성화돼
> 영향 범위를 알 수 없으므로 **별도 티켓**으로 남긴다.

**폴링을 쓰고 SSE 를 안 쓴 이유**: `EventSource` 가 Authorization 헤더를 못 실어 토큰을 쿼리
파라미터로 넘기는 우회가 필요하다(기존 `EarningsScreenerPage` 가 그렇게 한다). 5턴짜리 단발
작업에 그 위험을 감수할 이유가 없다.

---

## 6. 프롬프트 위치

`backend/.../controlroom/CrewPrompts.java` — 목업(`docs/mockups/myplatform_control_room.html` 의
`PROMPTS`)을 그대로 이식하고 컨텍스트만 실제 스냅샷으로 바꿨다. 5턴 정의(`Step` enum)도 같은 파일에
있어 순서·역할·수신자가 한곳에 모여 있다.

컨텍스트 조립은 `CrewContextBuilder`(순수 함수). **UTF-8 8KB 상한**을 넘으면 FLAGGED 를
**중요도 낮은 것부터** 잘라내고, 잘라낸 사실을 본문에 `N건 생략` 으로 남긴다 —
생략을 숨기면 FIREWALL 이 "플래그에 없으니 문제 없다"고 판단한다. KPI·캘린더·불변식은 자르지 않는다.

---

## 7. 설정 / 운영

### 7-1. 환경변수

```bash
# 서버 .env
ANTHROPIC_API_KEY=sk-ant-...           # 미설정이면 크루만 DISABLED (앱 전체는 정상 기동)
CONTROL_ROOM_CREW_MODEL=claude-opus-5  # 기본값. 기동 시 GET /v1/models 로 실재 확인
CONTROL_ROOM_CREW_DAILY_LIMIT=30       # 하루 세션 상한. 0 이하면 무제한
```

**⚠ `.env` 만 고치고 `restart` 하면 반영되지 않는다.** env_file 주입은 컨테이너 *생성* 시점에
고정된다. 그래서 compose `environment:` 에 `ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}` 로
**명시 이중배선**해 뒀고(NAVER/GEMINI/DART 와 동일 관행), 적용은 recreate 로 한다:

```bash
docker compose up -d --force-recreate backend
```

키를 넣기 전까지 화면은 크루 카드가 `OFFLINE` 이고 **비활성 사유가 그대로 표시**된다
("ANTHROPIC_API_KEY 미설정 — 크루 비활성"). 버튼만 죽어 있고 이유를 모르는 상태를 만들지 않는다.

### 7-2. 문서는 이미지에 박힌다

운영 이미지엔 `app.jar` 하나만 들어간다(레포 체크아웃 없음). 그래서 `processResources` 가
판정 캘린더·FLAGGED·CLAUDE.md 를 **jar 안 `classpath:control-room/`** 으로 복사한다.

→ **문서를 고치려면 커밋 + 재배포**다. 급하게 서버에서만 고쳐야 하면 볼륨을 마운트하고
`CONTROL_ROOM_DOCS_DIR` 로 그 경로를 지정하면 classpath 보다 우선한다.

### 7-3. 비용

`claude-opus-5` = $5 / input MTok, $25 / output MTok (2026-08 공식 문서 기준).
1세션 = 5턴, 입력 누적 ≈ 19K 토큰 / 출력 ≤ 5K → **약 $0.12~0.22 (170~320원)**.
일 30세션 상한이면 최악 하루 약 9,600원.

가드 4겹: **5턴 고정** / **동시 1건**(단일 스레드 실행기 + RUNNING 존재 시 거부) /
**일일 상한**(초과 시 429, 조용한 스킵 없음) / **컨텍스트 8KB**.

턴별 `model`·`stop_reason`·입출력 토큰이 `crew_message` 에 남아 사후 비용 추적이 가능하다.
`usage` 가 없는 턴은 0 이 아니라 **NULL** 로 두고 합계에서 뺀다(`Usage.complete=false` 로 노출).

### 7-4. Opus 5 의 max_tokens 함정

Opus 5 는 **adaptive thinking 이 기본 ON** 이고 사고 토큰이 `max_tokens` 예산을 함께 쓴다.
`effort` 를 낮추지 않으면 1000 토큰이 사고에 먹혀 본문이 잘린다. 그래서:

- 일반 턴: `effort=low`, `max_tokens=1000`
- FIREWALL 검토 턴만: `effort=medium`, `max_tokens=2000` (판단 품질이 필요한 유일한 턴)

`stop_reason=max_tokens` 는 예외가 아니라 정상 응답이지만 **본문이 잘린 것**이므로 그대로
올려보내 화면이 **"응답 잘림"** 배지를 띄운다. 자동 재시도는 하지 않는다.

### 7-5. 재기동

프로세스가 죽으면 `RUNNING` 세션이 고아로 남는다. 그대로 두면 **고아 1건이 동시 1건 가드를 영구히
막는다.** 기동 시 `failStrandedSessionsOnStartup` 이 `FAILED` + 사유로 정리한다(조용히 지우지 않는다).

---

## 8. 프론트

`/control-room` (ADMIN, lazy chunk). 목업 레이아웃에서 **좌측 사이드바는 제외**했다 — 8개 메뉴 중
대응 화면이 대부분 없어서. GNB 복귀 링크(`← 주식 허브`) 하나만 둔다.

> **IA 규칙의 명시적 예외**: CLAUDE.md 는 "새 주식 화면(라우트)을 만들지 말고 탭/서브탭에 흡수"라고
> 한다. 관제실은 **종목을 보는 주식 허브 화면이 아니라 판정/게이트/플래그를 보는 운영 콘솔**이라
> GNB 4탭(오늘/시장/발굴/매매)에 들어갈 자리가 없다. 이 예외는 관제실 하나로 끝낸다 —
> 종목·시세·추천을 보여주는 화면을 새로 만들 땐 여전히 탭에 흡수할 것.

**팔레트는 `.control-room` 스코프 한정**이다. 목업은 `:root` 에 토큰을 깔았는데 그대로 넣으면
전역 `common.css` 의 `--bg-page`·`--text-primary` 를 덮어써 **관제실을 연 순간 다른 화면이 깨진다.**
전부 `--cr-*` 로 격리했다. 새 색을 추가할 때도 `:root` 로 올리지 말 것.

**모델 출력을 HTML 로 신뢰하지 않는다.** 목업의 `fmt()` 는 LLM 응답을 `innerHTML` 에 그대로 넣었다.
현재는 전체 이스케이프 후 `[승인]/[조건부]/[반려]` 첫 줄만 배지로 치환한다(XSS 회귀 테스트 있음).

---

## 9. 한계 / 아직 검증 안 된 것

- **크루 5턴을 실제 API 로 돌린 적이 없다.** 구현 시점(2026-08-24)에 `ANTHROPIC_API_KEY` 가 없어
  컴파일·단위 테스트까지만 확인했다. 첫 실행 때 응답 형태·잘림 처리·토큰 기록을 1회 눈으로 확인할 것.
- **브라우저 실측 미완.** 빌드와 유닛 테스트는 통과했지만 우측 380px 패널 스크롤, 달력 칸 텍스트
  잘림은 실제로 띄워봐야 안다.
- **크루 결론의 품질은 측정되지 않았다.** 이 저장소의 원칙대로라면 "검증 전엔 승격 금지"인데,
  크루 결론은 애초에 **사람에게 주는 정리**이지 산식·봇에 편입되는 신호가 아니다. 그 경계를 넘지 말 것.
- **판정 캘린더의 `title` 은 사람이 두 곳에 맞춰 적어야 한다**(표 ↔ YAML). 어긋나면 조용히 사라지지
  않고 "미등록 판정"으로 뜨지만, 자동 동기화는 아니다.

---

## 10. 관련 파일

| 구분 | 위치 |
|---|---|
| 목업(원본 스펙) | `docs/mockups/myplatform_control_room.html` |
| 판정 캘린더 | `docs/SCHEDULE_DECISIONS.md` (끝의 기계 판독 블록) |
| FLAGGED | `docs/CONTROL_ROOM_FLAGS.md` |
| 백엔드 | `backend/src/main/java/com/myplatform/backend/controlroom/` |
| 마이그레이션 | `V54__create_crew_tables.sql` |
| 프론트 | `frontend/src/views/ControlRoomView.vue`, `frontend/src/components/controlroom/` |
| 표시 로직(순수) | `frontend/src/utils/controlRoomFormat.js` |
