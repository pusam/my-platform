package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {

    /**
     * 설정 키로 조회
     */
    Optional<BotConfig> findByConfigKey(String configKey);

    /**
     * 일일 손실 브레이커 trip — <b>조건부 UPDATE</b>(V38 서킷브레이커).
     *
     * <p>이미 오늘로 발동돼 있으면 0행(no-op) — 반환 1 == "이번 호출이 최초 발동"이라
     * 텔레그램/감사 1회 멱등이 경합·재시작에도 보장된다. 엔티티 load-modify-save 대신
     * 타깃 컬럼만 UPDATE 라 다른 필드 클로버 없음.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE BotConfig c
           SET c.dailyLossBreakerTrippedDate = :today
         WHERE c.configKey = :key
           AND (c.dailyLossBreakerTrippedDate IS NULL OR c.dailyLossBreakerTrippedDate <> :today)
        """)
    int tripDailyLossBreaker(@Param("key") String configKey, @Param("today") LocalDate today);

    /**
     * 일일 손실 브레이커 수동 해제(ADMIN) — trippedDate NULL 세팅. 반환 1 == 실제 해제됨(발동 상태였음).
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE BotConfig c
           SET c.dailyLossBreakerTrippedDate = NULL
         WHERE c.configKey = :key
           AND c.dailyLossBreakerTrippedDate IS NOT NULL
        """)
    int releaseDailyLossBreaker(@Param("key") String configKey);
}
