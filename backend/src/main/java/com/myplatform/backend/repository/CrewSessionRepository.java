package com.myplatform.backend.repository;

import com.myplatform.backend.entity.CrewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CrewSessionRepository extends JpaRepository<CrewSession, Long> {

    /** 동시 실행 가드 — RUNNING 이 1건이라도 있으면 새 세션을 거부한다(연타·중복 과금 방지). */
    long countByStatus(CrewSession.Status status);

    /** 일일 상한 가드 — 시작 시각 기준 당일 세션 수. 실패한 세션도 호출이 나갔으므로 함께 센다. */
    long countByStartedAtBetween(LocalDateTime from, LocalDateTime to);

    /** 최근 세션 목록(스레드 복원·이력 화면). */
    List<CrewSession> findTop20ByOrderByStartedAtDesc();
}
