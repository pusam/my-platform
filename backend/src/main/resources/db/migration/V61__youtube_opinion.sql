-- V61: 유튜브 투자 의견 — 참고 표시·기록 전용 (2026-09-28)
--
-- 흐름: 관리자가 영상(허용된 YouTube 주소의 영상 ID)·게시 시각·출처가 있는 타임스탬프 자막을 등록
--       → Gemini 가 발언을 뽑고 → 결정적 규칙이 검증(종목 마스터·자막 원문 대조)해 저장 → 화면은 저장 결과만 읽는다.
--
-- ⚠ 이 테이블들은 추천 점수·순위·BUY 판정·봇·signal_outcome·stock_catalyst 와 **섞지 않는다** — 산식 미편입.
-- ⚠ 공개 영상 자막은 YouTube Data API 로 받을 수 없다(captions.download 는 영상 편집 권한 필요) — 자동 수집 미연결.
--    자동 수집 제공자를 붙이더라도 같은 적재 경로(자막 → 분석 실행)로 들어오게 한다.
-- ⚠ 재분석은 새 실행(yt_analysis_run)과 그 실행의 발언 행을 만든다 — 화면은 영상의 current_run_id 만 읽으므로
--    현재 의견은 중복되지 않고, 지난 실행의 행이 그대로 남아 바뀐 이력이 된다.

CREATE TABLE IF NOT EXISTS yt_person (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL COMMENT '발언자 표시명 — 관리자가 명시 등록(모델이 만들지 않는다)',
    name_key VARCHAR(50) NOT NULL COMMENT '공백 제거·소문자 정규화 이름 — 같은 인물 판정 키',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_ytp_name_key (name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='유튜브 의견 발언자 — 관리자 등록. 신뢰도·성적 없음(인물 순위는 범위 밖)';

CREATE TABLE IF NOT EXISTS yt_video (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id VARCHAR(11) NOT NULL COMMENT 'YouTube 영상 ID — 허용된 주소에서만 추출',
    title VARCHAR(300) NOT NULL COMMENT '관리자 입력(서버가 영상 페이지를 가져오지 않는다)',
    channel_id VARCHAR(64) NOT NULL COMMENT '설정 youtube-opinion.channels 에 있는 채널만 허용',
    channel_name VARCHAR(100) NOT NULL COMMENT '등록 시점 설정 이름 스냅샷',
    published_at DATETIME NOT NULL COMMENT '영상 게시 시각(KST) — 집계 창의 기준',
    source_note VARCHAR(300) NOT NULL COMMENT '자막 출처(누가 어떤 방식으로 제공했는지)',
    duplicate_of VARCHAR(11) NULL COMMENT '재업로드·편집본이면 원본 영상 ID — 집계에서 제외',
    status VARCHAR(20) NOT NULL COMMENT 'REGISTERED(자막 없음)/TRANSCRIPT_READY/ANALYZING/ANALYZED/FAILED — 마지막 시도 기준',
    current_run_id BIGINT NULL COMMENT '화면이 읽는 마지막 성공 분석 실행 — 실패한 재분석은 이 값을 바꾸지 않는다',
    last_attempt_at DATETIME NULL,
    last_success_at DATETIME NULL,
    last_error VARCHAR(500) NULL COMMENT '마지막 실패 원인 — 성공하면 지운다',
    registered_by VARCHAR(50) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '최초 수집(등록) 시각',
    updated_at DATETIME NULL,
    UNIQUE KEY uq_ytv_video_id (video_id),
    INDEX idx_ytv_published (published_at),
    INDEX idx_ytv_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='유튜브 의견 분석 대상 영상 — 같은 영상 재등록은 새 행을 만들지 않는다';

CREATE TABLE IF NOT EXISTS yt_video_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id VARCHAR(11) NOT NULL,
    person_id BIGINT NOT NULL,
    role VARCHAR(10) NOT NULL COMMENT 'HOST(채널 운영자)/GUEST(출연자)',
    UNIQUE KEY uq_ytvp_video_person (video_id, person_id),
    INDEX idx_ytvp_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='영상별 출연자 — 발언자 연결은 이 명단 안에서만(추측 금지)';

CREATE TABLE IF NOT EXISTS yt_transcript (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id VARCHAR(11) NOT NULL,
    version INT NOT NULL COMMENT '영상별 1부터 — 내용이 바뀔 때만 올라간다',
    source_format VARCHAR(10) NOT NULL COMMENT 'SRT/VTT/TEXT',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '정규화 큐의 해시 — 같은 자막 재등록은 새 버전을 만들지 않는다',
    cue_count INT NOT NULL,
    duration_sec INT NOT NULL,
    cues_json MEDIUMTEXT NOT NULL COMMENT '정규화 큐 [{s,e,sp,t}] — 태그 제거된 평문, 분석·원문 대조용',
    uploaded_by VARCHAR(50) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_ytt_video_version (video_id, version),
    UNIQUE KEY uq_ytt_video_hash (video_id, content_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='관리자 등록 타임스탬프 자막(버전 이력)';

CREATE TABLE IF NOT EXISTS yt_analysis_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id VARCHAR(11) NOT NULL,
    transcript_id BIGINT NOT NULL,
    model VARCHAR(80) NOT NULL COMMENT '분석 모델(gemini.api.url 의 models/ 세그먼트)',
    prompt_version VARCHAR(20) NOT NULL,
    status VARCHAR(12) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED — 실패 실행은 발언 행을 남기지 않는다',
    chunk_count INT NOT NULL DEFAULT 0,
    statement_count INT NOT NULL DEFAULT 0,
    dropped_count INT NOT NULL DEFAULT 0 COMMENT '형식 불량으로 버린 모델 출력 수',
    error VARCHAR(500) NULL,
    requested_by VARCHAR(50) NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL COMMENT '분석 완료 시각',
    INDEX idx_ytar_video (video_id, started_at),
    INDEX idx_ytar_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='유튜브 의견 분석 실행 — 모델·프롬프트 버전과 결과 이력';

CREATE TABLE IF NOT EXISTS yt_opinion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    video_id VARCHAR(11) NOT NULL,
    statement_key VARCHAR(64) NOT NULL COMMENT '실행 안 중복 방지 + 재분석 간 검토 승계 키',
    speaker_label VARCHAR(50) NULL COMMENT '자막에 표시된 화자 그대로 — 없으면 NULL',
    person_id BIGINT NULL COMMENT '출연자 명단과 결정적으로 맞을 때만 — 모르면 NULL(인물 수 집계 제외)',
    speaker_role VARCHAR(10) NULL COMMENT 'HOST/GUEST — person_id 가 있을 때만',
    start_sec INT NOT NULL COMMENT '발언 시작(초) — 원문 링크 시점',
    end_sec INT NOT NULL,
    stock_name_raw VARCHAR(60) NOT NULL COMMENT '발언에 나온 종목 표기 그대로',
    stock_code VARCHAR(10) NULL COMMENT '종목 마스터로 확인된 코드 — 확인 안 되면 NULL(검토 대상)',
    mapping_status VARCHAR(16) NOT NULL COMMENT 'VERIFIED/AMBIGUOUS/UNMATCHED/CODE_MISMATCH/MANUAL',
    stance VARCHAR(16) NOT NULL COMMENT 'POSITIVE/NEGATIVE/NEUTRAL/CONDITIONAL/UNDETERMINABLE',
    statement_type VARCHAR(16) NOT NULL COMMENT 'CURRENT_VIEW/QUOTE/PAST_REVIEW/MENTION — CURRENT_VIEW 만 집계',
    claim_summary VARCHAR(300) NOT NULL,
    rationale VARCHAR(500) NULL,
    conditions VARCHAR(300) NULL COMMENT '전제 조건 — 없으면 NULL(모델 보충 금지)',
    horizon VARCHAR(40) NULL COMMENT '발언 원문에 있을 때만',
    target_price DECIMAL(15,2) NULL COMMENT '발언 원문의 숫자와 맞을 때만',
    evidence_quote VARCHAR(300) NOT NULL COMMENT '자막 원문 발췌(짧게)',
    review_status VARCHAR(12) NOT NULL COMMENT 'AUTO_OK/NEEDS_REVIEW/APPROVED/REJECTED',
    review_reasons VARCHAR(300) NULL,
    reviewed_by VARCHAR(50) NULL,
    reviewed_at DATETIME NULL,
    analyzed_at DATETIME NOT NULL COMMENT '분석 완료 시각(실행 종료)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_yto_run_key (run_id, statement_key),
    INDEX idx_yto_stock (stock_code, run_id),
    INDEX idx_yto_video (video_id, run_id),
    INDEX idx_yto_review (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='영상 발언 단위 종목 의견 — 참고 표시 전용, 추천 산식 미편입';
