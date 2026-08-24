package com.myplatform.backend.repository;

import com.myplatform.backend.entity.CrewMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CrewMessageRepository extends JpaRepository<CrewMessage, Long> {

    /** 세션 스레드 — 턴 순서대로. 폴링이 매번 호출하므로 인덱스(session_id, turn_no) 필수. */
    List<CrewMessage> findBySessionIdOrderByTurnNoAsc(Long sessionId);
}
