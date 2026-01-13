package com.myplatform.backend.repository;

import com.myplatform.backend.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
            String email, String token, LocalDateTime now);

    void deleteByEmail(String email);

    void deleteByExpiresAtBefore(LocalDateTime now);
}

