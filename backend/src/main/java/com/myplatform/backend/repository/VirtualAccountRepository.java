package com.myplatform.backend.repository;

import com.myplatform.backend.entity.VirtualAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 가상 계좌 Repository
 */
@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    /**
     * 활성화된 계좌 조회 (여러 개일 경우 가장 최근 것)
     */
    Optional<VirtualAccount> findFirstByIsActiveTrueOrderByIdDesc();

    /**
     * 활성화된 계좌 조회 - 비관적 락 적용 (동시성 제어)
     * 매수/매도 시 잔고 동기화를 위해 사용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM VirtualAccount a WHERE a.isActive = true ORDER BY a.id DESC LIMIT 1")
    Optional<VirtualAccount> findFirstByIsActiveTrueWithLock();

    /**
     * ID로 계좌 조회 - 비관적 락 적용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM VirtualAccount a WHERE a.id = :id")
    Optional<VirtualAccount> findByIdWithLock(@Param("id") Long id);

    /**
     * 계좌명으로 조회
     */
    Optional<VirtualAccount> findByAccountName(String accountName);
}
