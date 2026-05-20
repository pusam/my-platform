-- AI 종합 추천: '성장성' 별도 LONG 트랙 추가
-- 매출/이익 성장률 + PEG 종합 (0-20점). 가치(저평가)와 분리 — 시클리컬/성장주가
-- 밸류 지표만으로 저평가되는 한계를 보완하는 별도 축.
-- default -1 = NA(데이터 없음/미산출): 기존 행은 다음 스냅샷 재계산 전까지 UI 에 "—" 로 노출.
ALTER TABLE recommendation_snapshot
    ADD COLUMN growth INT NOT NULL DEFAULT -1
        COMMENT '성장성 점수 (매출·이익 성장률·PEG, -1=NA)';
