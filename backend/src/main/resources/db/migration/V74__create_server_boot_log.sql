-- V74: 백엔드 부팅 기록 (2026-11-10, 개선 후보 4번 '장중 배포 가드와 재시작 기록')
--
-- ## 왜
--
-- 추천 성적의 표본 시작일은 '08:30~15:50 에 재시작이 없던 거래일'이어야 하고(CLAUDE.md §7), 재무 수집(08:30~09:12, 15:38~16:11)·
-- 16:15 수급 확정치 수집 중에도 재시작하면 안 된다. 그런데 재시작이 언제 있었는지는 서버에서 `docker inspect` 의 StartedAt 을
-- 손으로 봐야 알았고, 그 값은 마지막 한 번뿐이다(그 전 재시작은 남지 않는다). 2026년 가을 장중 푸시로 표본 시작일이 네 번 밀렸다.
-- 그래서 부팅할 때마다 한 행을 쌓고 관제실이 읽는다(읽기 전용 — §7).
--
-- ## 칸
--
-- started_at : JVM 이 뜬 시각(KST). 재시작 '순간'에 가장 가깝다 — 부팅 완료(ready_at)는 Flyway·JPA 초기화로 30초~2분 늦다.
-- ready_at   : 부팅 완료(ApplicationReadyEvent) 시각.
-- host       : 컨테이너 호스트 이름(= 컨테이너 id). 같은 값이면 같은 컨테이너의 재시작(OOM·restart 정책), 바뀌었으면 재생성(배포·수동 recreate).
-- revision   : 이미지가 담은 커밋(APP_REVISION, deploy.yml 이 빌드 인자로 넣는다). 모르면 NULL.
--
-- 기록이 실패해도 부팅은 계속된다(ServerBootRecorder 가 예외를 WARN 으로 삼킨다). 지우는 잡은 없다 — 하루 몇 행이다.

CREATE TABLE IF NOT EXISTS server_boot_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at DATETIME     NOT NULL,
    ready_at   DATETIME     NOT NULL,
    host       VARCHAR(100) NULL,
    revision   VARCHAR(64)  NULL,
    INDEX idx_sbl_started_at (started_at)
);
