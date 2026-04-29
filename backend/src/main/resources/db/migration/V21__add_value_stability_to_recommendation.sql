-- AI 종합 추천: 6번째 카테고리 '가치/안정성' 추가
-- PBR/ROE/부채비율/흑자 + 대주주 리스크 페널티 종합 (0-20점)
ALTER TABLE recommendation_snapshot
    ADD COLUMN value_stability INT NOT NULL DEFAULT 0
        COMMENT '가치/안정성 점수 (PBR·ROE·부채비율·리스크)';
