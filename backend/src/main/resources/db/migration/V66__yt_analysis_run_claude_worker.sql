-- 유튜브 의견 분석기 선택(GEMINI/CLAUDE) + Claude 로컬 작업자 상태 (2026-10-01)
--
-- CLAUDE 분석은 서버가 직접 부르지 않는다 — 실행 기록을 대기열(status=QUEUED)에 올리면 구독 로그인이 된 로컬 PC 의 작업자가
-- 임대(lease_token·lease_until)를 받아 실행하고 모델 응답 원문만 돌려준다. 해석·검증·저장은 Gemini 와 같은 경로다.
-- 새 인프라(큐·브로커) 없이 기존 실행 기록으로 상태를 관리한다:
--   QUEUED   작업자가 아직 안 가져감
--   RUNNING  작업자가 임대 중(lease_until 이 지나면 다시 대기열로 — attempts 상한 넘으면 FAILED)
--   WAITING  사용량 한도(QUOTA)·로그인 만료(LOGIN)로 next_attempt_at 까지 대기 — 실패도 '의견 없음'도 아니다
-- 기존 행은 전부 Gemini 실행이다(analyzer 기본값). status 길이(12)는 새 값(QUEUED·WAITING)에 충분하다.
ALTER TABLE yt_analysis_run
    ADD COLUMN analyzer VARCHAR(10) NOT NULL DEFAULT 'GEMINI' COMMENT '분석기 — GEMINI(서버 직접) · CLAUDE(로컬 작업자)',
    ADD COLUMN requested_model VARCHAR(80) NULL COMMENT '요청 모델(별칭 가능) — 실제 응답 모델은 model',
    ADD COLUMN lease_token VARCHAR(36) NULL COMMENT 'CLAUDE 작업 임대 토큰',
    ADD COLUMN lease_until DATETIME NULL COMMENT '임대 만료 — 지나면 다시 대기열',
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 COMMENT '작업자 실행 시도 수(대기는 세지 않음)',
    ADD COLUMN next_attempt_at DATETIME NULL COMMENT 'WAITING 이 다시 집힐 수 있는 시각',
    ADD COLUMN wait_reason VARCHAR(20) NULL COMMENT 'QUOTA(사용량 한도) · LOGIN(로그인 만료)',
    ADD COLUMN worker_id VARCHAR(60) NULL COMMENT '작업자 식별(로그용)';

CREATE INDEX idx_ytar_analyzer_status ON yt_analysis_run (analyzer, status);
