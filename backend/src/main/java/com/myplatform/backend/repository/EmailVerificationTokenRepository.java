package com.myplatform.backend.repository;

import com.myplatform.backend.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByEmailAndTokenAndVerifiedFalseAndExpiresAtAfter(
            String email, String token, LocalDateTime now);

    void deleteByEmail(String email);

    void deleteByExpiresAtBefore(LocalDateTime now);
}

