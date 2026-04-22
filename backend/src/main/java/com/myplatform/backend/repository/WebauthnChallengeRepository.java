package com.myplatform.backend.repository;

import com.myplatform.backend.entity.WebauthnChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WebauthnChallengeRepository extends JpaRepository<WebauthnChallenge, Long> {

    Optional<WebauthnChallenge> findFirstBySessionKeyAndCeremonyOrderByCreatedAtDesc(
            String sessionKey, WebauthnChallenge.Ceremony ceremony);

    @Modifying
    @Query("DELETE FROM WebauthnChallenge c WHERE c.sessionKey = :sessionKey AND c.ceremony = :ceremony")
    int deleteBySessionKeyAndCeremony(@Param("sessionKey") String sessionKey,
                                       @Param("ceremony") WebauthnChallenge.Ceremony ceremony);

    @Modifying
    @Query("DELETE FROM WebauthnChallenge c WHERE c.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
