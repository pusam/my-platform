# 유튜브 의견 Claude 로컬 작업자

유튜브 참고 의견(§4e, 표시 전용) 분석을 **Claude 구독**으로 돌리는 로컬 작업자다. 서버는 Claude 를 부르지 않는다 —
분석 요청을 대기열(`yt_analysis_run.status=QUEUED`)에 올릴 뿐이고, 이 작업자가 관리자 API 로 작업을 **임대**해 같은
프롬프트로 Claude 를 돌린 뒤 **응답 원문만** 돌려준다. 해석·검증(`OpinionValidator`)·저장·검토 승계는 서버가 Gemini 와 같은
경로로 한다. 외부 의존성 없음(Node 20+ 내장 모듈만).

## 구독으로 써도 되나 — 확인한 근거 (2026-10-01)

- 공식 안내 [Use the Claude Agent SDK with your Claude plan](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)
  (2026-06-16, 6/15 갱신): 예정됐던 별도 크레딧 전환을 **보류**했고, 현재는 *"Claude Agent SDK, `claude -p`, and third-party app
  usage still draw from your subscription's usage limits."* — 구독 사용량 한도에서 차감된다. 별도 API 과금이 아니다.
  바뀌면 시행 전에 알린다고 적혀 있다 — 쓰기 전에 다시 확인할 것.
- [Claude Code 인증 문서](https://code.claude.com/docs/en/iam) 의 인증 우선순위: 클라우드 공급자 변수 → `ANTHROPIC_AUTH_TOKEN`
  → `ANTHROPIC_API_KEY`(`-p` 에선 **있으면 항상 쓴다**) → `apiKeyHelper` → `CLAUDE_CODE_OAUTH_TOKEN` → 프로필 → `/login` 구독.
  즉 **API 키 환경변수가 하나라도 있으면 구독이 아니라 API 과금**이 된다. 그래서 이 작업자는 자식 프로세스에 허용 목록 환경변수만
  넘기고(API 키·토큰·게이트웨이·클라우드 변수 전부 제외), 실행마다 `apiKeySource` 가 `none` 인지 확인해 아니면 그 자리에서 멈춘다.
  `--bare` 는 OAuth 를 읽지 않고 API 키만 써서 쓰지 않는다.
- [추가 사용량(extra usage)](https://support.claude.com/en/articles/12429409): 기본 **꺼짐**. 켜 두면 한도를 넘은 사용분이 표준 API
  요금으로 **별도 청구**된다. 이 작업자는 그 설정을 바꾸지 않는다 — 구독 한도 안에서만 쓰려면 claude.ai 설정에서 꺼 둔 채로 둘 것.
  한도에 걸리면 작업자는 실행을 `WAITING(QUOTA)` 로 보고하고 재설정 시각까지 기다린다.

## 준비

1. 이 PC 에서 Claude 구독 계정으로 로그인한다(한 번):
   ```
   claude auth login
   claude auth status     # "loggedIn": true, "apiProvider": "firstParty" 확인
   ```
   코드 작업용 Claude 세션과 분석 환경을 완전히 나누고 싶으면 작업자 전용 설정 폴더를 따로 만든다:
   `set CLAUDE_CONFIG_DIR=C:\claude-worker` → `claude auth login` → 아래 `YT_WORKER_CLAUDE_CONFIG_DIR` 에 같은 폴더.
2. 서버 `.env` 에 `YOUTUBE_OPINION_ANALYZER=CLAUDE` (기본 GEMINI — **작업자를 먼저 준비하고 바꿀 것**, 안 그러면 분석이 대기열에
   머문다) → backend 재생성.
3. 환경변수(이 PC 에서만, 저장소·서버에 넣지 않는다):

| 변수 | 필수 | 뜻 |
| --- | --- | --- |
| `YT_WORKER_API_BASE` | ✅ | 서버 주소(예: `https://…`). 끝 `/` 없이 |
| `YT_WORKER_USERNAME` / `YT_WORKER_PASSWORD` | ✅ | **관리자** 계정 — `/api/admin/**` 규칙 그대로. 출력하지 않는다 |
| `YT_WORKER_CLAUDE_BIN` |  | claude 실행 파일. 비우면 PATH 에서 찾고, Windows npm 래퍼(`claude.cmd`)면 같은 설치의 `bin/claude.exe` 를 쓴다 |
| `YT_WORKER_CLAUDE_CONFIG_DIR` |  | 작업자 전용 Claude 설정 폴더(위 1번) |
| `YT_WORKER_CALL_TIMEOUT_SEC` |  | 구간 1회 호출 제한(기본 180초) |
| `YT_WORKER_POLL_SEC` |  | 대기열 확인 간격(기본 60초) |
| `YT_WORKER_ID` |  | 로그용 이름(기본 호스트명) |

## 실행

```
node worker.mjs --once   # 한 건만 처리(없으면 바로 끝) — 처음엔 이걸로 확인
node worker.mjs          # 계속 대기열을 본다, Ctrl+C 로 멈춤
npm test                 # 규칙·처리 순서 테스트(가짜 실행기)
```

시작할 때 `claude auth status` 로 로그인을 먼저 확인하고(모델 호출 없음), 로그인이 안 됐거나 API 키 방식이면 서버에 아무것도
요청하지 않고 멈춘다(종료 코드 2).

## 동작과 안전장치

- **도구·환경 제한**: `claude -p --tools "" --strict-mcp-config --mcp-config {"mcpServers":{}} --setting-sources project
  --no-session-persistence --disable-slash-commands --system-prompt <추출기 역할>` 을 **빈 작업 폴더**에서 **셸 없이** 띄운다.
  셸·파일·웹·MCP·스킬이 없고 사용자 설정(훅·apiKeyHelper)도 읽지 않는다. 자막은 **stdin 으로만** 넘긴다 — 명령 인자·셸 문자열에
  들어가지 않는다(테스트가 고정). 자막 안의 지시문은 데이터로만 다룬다(시스템 프롬프트 + 서버 검증).
- **같은 입력 계약**: 사용자 메시지는 서버가 Gemini 에 보내는 본문(`OpinionPrompt.build`)과 같다. 시스템 프롬프트만 Claude Code 의
  기본 에이전트 프롬프트를 대체한다(`prompt_version` = `yt-opinion-v1/cs1`).
- **임대**: 작업마다 임대 토큰·만료(기본 30분)를 받고 구간마다 연장한다. 둘이 동시에 집어도 한쪽만 얻는다. 작업자가 죽으면 만료
  뒤 다시 대기열로 가고(시도 상한 3회), 서버 재시작은 Claude 실행을 건드리지 않는다. 만료 정리는 작업자가 다시 올 때뿐 아니라
  관리자가 새 분석을 요청할 때도 돈다 — 작업자를 그만 쓰고 GEMINI 로 되돌려도 죽은 임대가 Gemini 분석을 막지 않는다.
  같은 PC 에 작업자 둘은 잠금 파일로 막는다.
- **재시도**: 같은 구간의 일시 오류·JSON 아닌 응답만 한 번 더. 그 뒤 판단(대기/재시도/실패)은 서버가 한다.
- **대기·실패를 정상으로 위장하지 않는다**: 사용량 한도 → `WAITING(QUOTA)`(재설정 시각까지), 로그인 만료 → `WAITING(LOGIN)`
  (작업자는 멈추고 로그인을 기다린다), 잘못된 JSON·요청과 다른 모델·구독이 아닌 인증 → `FAILED`. 어느 것도 '의견 없음'이나
  성공으로 저장하지 않고, 실패해도 직전 성공 결과가 화면에 그대로 남는다.
- **모델 고정**: 서버가 준 모델(기본 `sonnet`)로만 부른다. `--fallback-model` 을 쓰지 않고, 실제 응답 모델이 요청과 다르면
  결과를 버린다. 유료 API·다른 모델로 조용히 바꾸지 않는다.
- **기록**: 실행마다 분석기(`CLAUDE`)·요청 모델·실제 모델·프롬프트 버전·시도 수·완료 시각이 `yt_analysis_run` 에 남는다.

## 범위 밖

- 자막을 자동으로 가져오지 않는다(§4e — 공개 영상 자막은 공식 API 로 못 받고 비공식 스크래핑은 약관 문제). 자막은 관리자가 등록한다.
- 결과는 표시 전용이다 — 추천 점수·순위·봇·시그널과 섞이지 않는다(`YoutubeOpinionIsolationTest`).
