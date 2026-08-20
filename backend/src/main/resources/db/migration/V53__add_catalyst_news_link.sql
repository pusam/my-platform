-- V53 (2026-08-20): 재료 분류 근거 뉴스 링크 저장.
-- resolveNewsLink 가 이미 계산하던 대표 뉴스 URL 을 텔레그램 알림에만 쓰고 버리던 것을 영속화 —
-- 배지/이력에서 "이 재료가 무슨 기사인지" 확인 경로 제공(표시 전용, 산식 미편입 §4b).
-- NULL = 링크 미확보(뉴스 항목에 링크 없음 등) — 프론트는 링크 생략(§4c).
ALTER TABLE stock_catalyst
    ADD COLUMN news_link VARCHAR(500) NULL COMMENT '분류 근거 대표 뉴스 URL (NULL=미확보)';
