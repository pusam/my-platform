package com.myplatform.backend.repository;

import com.myplatform.backend.entity.VirtualAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 가상 계좌 Repository
 */
@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    /**
     * 활성화된 계좌 조회
     */
    Optional<VirtualAccount> findByIsActiveTrue();

    /**
     * 계좌명으로 조회
     */
    Optional<VirtualAccount> findByAccountName(String accountName);
}
