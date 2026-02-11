-- AI 스코어링 컬럼 추가 (Gemini AI 매력도 점수)
ALTER TABLE ai_strategy_snapshot ADD COLUMN ai_score INT DEFAULT NULL;
ALTER TABLE ai_strategy_snapshot ADD COLUMN ai_comment VARCHAR(200) DEFAULT NULL;
ALTER TABLE ai_strategy_snapshot ADD COLUMN original_score INT DEFAULT NULL;
