package com.myplatform.backend.bootlog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ServerBootLogRepository extends JpaRepository<ServerBootLog, Long> {

    /** 관제실 '장중 재시작' 카드 — 조회 창 안의 부팅(오래된 순). */
    @Transactional(readOnly = true)
    List<ServerBootLog> findByStartedAtGreaterThanEqualOrderByStartedAtAsc(LocalDateTime from);

    /** 기록이 언제부터 있는가 — '기록 시작 이후 없음'과 '정말 없음'을 가르는 기준. */
    @Transactional(readOnly = true)
    Optional<ServerBootLog> findFirstByOrderByStartedAtAsc();
}
