-- P3-3: recommendation_snapshot 의 growth/value_stability -1=NA sentinel → NULL 전환 (§4c 결측은 null).
-- -1 sentinel 은 `score > 0`/`>= 0` 필터가 음수를 오작동시키는 위험 패턴(verdictFor NEGATIVE 오판 버그 전례).
-- 엔티티는 Integer(nullable) 로 전환, 표시/DTO 계약(-1=NA, UI "—")은 서비스 경계 변환으로 불변.
ALTER TABLE recommendation_snapshot
    MODIFY COLUMN value_stability INT NULL
        COMMENT '가치/안정성 점수 (PBR·ROE·부채비율·리스크). NULL=미산출(NA)';
ALTER TABLE recommendation_snapshot
    MODIFY COLUMN growth INT NULL
        COMMENT '성장성 점수 (매출·이익 성장률·PEG). NULL=미산출(NA)';
-- 기존 -1(NA) 데이터 백필 — V29 default(-1) 행 포함.
UPDATE recommendation_snapshot SET value_stability = NULL WHERE value_stability < 0;
UPDATE recommendation_snapshot SET growth = NULL WHERE growth < 0;
