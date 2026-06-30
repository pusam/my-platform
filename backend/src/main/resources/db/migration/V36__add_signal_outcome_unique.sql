-- V36: signal_outcome (signal_type, stock_code, signal_date) UNIQUE 제약 (P3-2 방어)
--
-- 배경: record() 는 앱레벨 dedup(findExisting)만 있고 DB 제약이 없어, 멀티 인스턴스/경합 시
--       같은 (signal_type, stock_code, signal_date) 중복 INSERT 가 가능(드묾). DB UNIQUE 로 방어.
--       record() 는 INSERT 를 REQUIRES_NEW 로 격리 + DataIntegrityViolationException benign 처리로 보완.
--
-- 순서: 1) 기존 중복 제거(최소 id 보존) -> 2) UNIQUE 추가. (원자적 — 배포 시 dedup 누락으로 실패 안 함)
-- 참고: 기존 idx_so_type_date(signal_type, signal_date)는 컬럼 순서가 달라(이 UNIQUE 는 중간에
--       stock_code 가 끼어 prefix 로 커버 안 됨) -> 유지(중복 인덱스 아님).

-- 1) 중복 정리: 같은 3-튜플 중 최소 id 만 보존(자기조인). 중복은 같은 날 경합 산물이라 평가상태 동일이 일반적.
DELETE so FROM signal_outcome so
  JOIN signal_outcome keep
    ON so.signal_type = keep.signal_type
   AND so.stock_code  = keep.stock_code
   AND so.signal_date = keep.signal_date
   AND so.id > keep.id;

-- 2) UNIQUE 제약 추가
ALTER TABLE signal_outcome
  ADD CONSTRAINT uq_so_type_code_date UNIQUE (signal_type, stock_code, signal_date);
