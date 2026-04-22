package com.myplatform.backend.repository;

import com.myplatform.backend.entity.WebauthnCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WebauthnCredentialRepository extends JpaRepository<WebauthnCredential, Long> {

    List<WebauthnCredential> findAllByUserId(Long userId);

    @Query("SELECT c FROM WebauthnCredential c WHERE c.credentialId = :credentialId")
    Optional<WebauthnCredential> findByCredentialId(@Param("credentialId") byte[] credentialId);

    @Modifying
    @Query("DELETE FROM WebauthnCredential c WHERE c.id = :id AND c.userId = :userId")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    long countByUserId(Long userId);
}
