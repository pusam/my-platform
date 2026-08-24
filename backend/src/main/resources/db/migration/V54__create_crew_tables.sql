-- V54: 판정 관제실 AI 크루 세션 기록 (crew_session / crew_message)
--
-- 목적: 오퍼레이터 지시 1건 → 에렌/SCOUT/FIREWALL 5턴 대화를 그대로 보존한다.
--       **읽기 전용 레이어** — 크루는 DB/파일/봇 어디에도 쓰지 않는다. 이 두 테이블만이
--       관제실이 쓰는 유일한 저장소이고, 담기는 것은 "무엇을 물었고 무엇이라 답했는가"뿐이다.
--       결론은 텍스트와 "액션 제안"이며 실행은 사람이 한다.
--
-- 흐름:
--   1. POST /api/control-room/crew/sessions — RUNNING 으로 INSERT
--   2. 5턴 순차 실행(고정, 자동 재시도·루프 없음) — 턴마다 crew_message INSERT
--   3. 전 턴 성공 → COMPLETED / 어느 턴이든 실패 → FAILED + failure_reason (재시도는 사람이)
--
-- NULL 의미(§4c):
--   - finished_at NULL = 아직 진행 중(실패로 위장하지 않음)
--   - input_tokens/output_tokens NULL = 응답에 usage 가 없었음(0 으로 위장 금지 — 비용 집계에서 제외)
--   - stop_reason 'max_tokens' = 응답 잘림. 화면에 "응답 잘림" 배지로 명시하고 자동 재시도하지 않는다.

CREATE TABLE IF NOT EXISTS crew_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(100) COMMENT '지시한 운영자 계정명 (SecurityContext username)',
    instruction TEXT NOT NULL COMMENT '오퍼레이터 지시 원문',
    status VARCHAR(20) NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    failure_reason TEXT COMMENT 'FAILED 일 때 사유 — 자동 재시도 안 함, 사람이 판단',
    model VARCHAR(100) COMMENT '세션 시작 시점에 설정돼 있던 모델 ID (설정이 바뀌어도 과거 세션 재현 가능)',
    context_bytes INT COMMENT '크루에게 주입한 스냅샷 컨텍스트 바이트 수',
    omitted_flags INT COMMENT '컨텍스트 상한 초과로 잘라낸 FLAGGED 건수 (0=생략 없음, 화면에 "N건 생략" 표기)',
    started_at DATETIME NOT NULL,
    finished_at DATETIME COMMENT 'NULL=진행 중',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cs_started (started_at),
    INDEX idx_cs_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='관제실 AI 크루 세션 — 읽기 전용 레이어, 실행 권한 없음';

CREATE TABLE IF NOT EXISTS crew_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    turn_no INT NOT NULL COMMENT '1~5 고정 (루프·재시도로 늘어나지 않는다)',
    agent VARCHAR(20) NOT NULL COMMENT 'OPERATOR / EREN / SCOUT / FIREWALL',
    phase VARCHAR(20) NOT NULL COMMENT 'ORDER / ROUTING / DRAFT / REVIEW / REVISE / CLOSING',
    content MEDIUMTEXT COMMENT '발언 원문',
    model VARCHAR(100) COMMENT '이 턴에 실제로 쓴 모델 ID — 비용 추적용',
    effort VARCHAR(10) COMMENT '이 턴의 output_config.effort (low/medium)',
    max_tokens INT COMMENT '이 턴에 건 max_tokens 상한',
    stop_reason VARCHAR(30) COMMENT 'end_turn / max_tokens / refusal 등. max_tokens=응답 잘림',
    input_tokens INT COMMENT 'usage.input_tokens — NULL=미수집(비용 집계 제외)',
    output_tokens INT COMMENT 'usage.output_tokens — NULL=미수집(비용 집계 제외)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_cm_session_turn (session_id, turn_no),
    INDEX idx_cm_session (session_id, turn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='관제실 크루 턴별 발언 + 토큰/모델 메타(비용 추적)';
