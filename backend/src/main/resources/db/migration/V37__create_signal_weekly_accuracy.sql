-- 시그널 예측력 주간 측정 스냅샷 (P1-6 측정 상설화)
--
-- 배경: 종합점수 4카테고리 적중률(P1-6)의 "2-4주 뒤 수동 재측정"을 주간 자동 배치로 상설화.
--       매주 일요일 저녁 크론이 (카테고리 × regime × 밴드) 적중률/평균 alpha/표본수를 집계해 1주 1행 저장.
--       시간에 따른 예측력 변화 추적(악화 감지·수급 역상관 지속 주차 카운트)이 목적.
--
-- 산식 무관: 종합점수/가중치는 건드리지 않음 — 측정 전용 스냅샷.
-- report_json 에 전체 크로스탭(regime 파티션별 카테고리/밴드 stats + 델타 + 경고)을 담아,
-- 표본 축적 후 3중(regime×카테고리×밴드) 집계 추가도 스키마 변경 없이 가능.

CREATE TABLE IF NOT EXISTS signal_weekly_accuracy (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    week_start      DATE         NOT NULL COMMENT '측정 주의 첫날 (월요일)',
    week_end        DATE         NOT NULL COMMENT '측정 주의 마지막 날 (일요일)',
    -- 집계 요약 (조회/추세용 헤드라인 컬럼)
    weekly_n        INT          NOT NULL DEFAULT 0 COMMENT '이번 주 평가완료 board 시그널 수',
    cumulative_n    INT          NOT NULL DEFAULT 0 COMMENT 'phase-38 컷오프 이후 누적 board 시그널 수',
    supply_inverted TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '누적 강세-수급 avg alpha<0 & n충분 = 역상관 지속 플래그(스트릭 카운트용)',
    -- 본문
    report_json     TEXT         NULL COMMENT '전체 크로스탭 JSON (regime 파티션별 카테고리/밴드 stats + 누적 델타 + 경고)',
    -- 메타
    generated_by    VARCHAR(50)  NOT NULL DEFAULT 'system' COMMENT 'system 또는 username(수동 트리거)',
    generated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '리포트 생성 시각',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 주 1행 — 같은 주 재생성 시 UPSERT (week_start 유일)
    UNIQUE KEY uk_swa_week_start (week_start),
    KEY idx_swa_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
