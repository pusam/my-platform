-- V3: 뉴스 감성 분석 컬럼 추가
-- 뉴스가 시장에 미치는 영향 (POSITIVE, NEGATIVE, NEUTRAL)

ALTER TABLE news_summary
ADD COLUMN IF NOT EXISTS sentiment VARCHAR(20) DEFAULT NULL
COMMENT '감성 분석 결과 (POSITIVE, NEGATIVE, NEUTRAL)';

-- 인덱스 추가 (감성별 필터링 성능 향상)
CREATE INDEX IF NOT EXISTS idx_news_summary_sentiment ON news_summary(sentiment);

-- source_url 컬럼 유니크 인덱스 추가 (중복 URL 방지)
-- 이미 중복 데이터가 있을 수 있으므로 무시하고 진행
ALTER TABLE news_summary
ADD UNIQUE INDEX IF NOT EXISTS idx_news_summary_source_url (source_url(255));
