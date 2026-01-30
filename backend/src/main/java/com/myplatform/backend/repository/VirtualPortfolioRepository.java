package com.myplatform.backend.repository;

import com.myplatform.backend.entity.VirtualPortfolio;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 가상 포트폴리오 Repository
 */
@Repository
public interface VirtualPortfolioRepository extends JpaRepository<VirtualPortfolio, Long> {

    /**
     * 계좌별 포트폴리오 조회
     */
    List<VirtualPortfolio> findByAccountId(Long accountId);

    /**
     * 계좌 + 종목코드로 조회
     */
    Optional<VirtualPortfolio> findByAccountIdAndStockCode(Long accountId, String stockCode);

    /**
     * 계좌 + 종목코드로 조회 - 비관적 락 적용 (동시성 제어)
     * 매수/매도 시 수량 동기화를 위해 사용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM VirtualPortfolio p WHERE p.accountId = :accountId AND p.stockCode = :stockCode")
    Optional<VirtualPortfolio> findByAccountIdAndStockCodeWithLock(
            @Param("accountId") Long accountId,
            @Param("stockCode") String stockCode);

    /**
     * 계좌 + 종목코드로 삭제
     */
    void deleteByAccountIdAndStockCode(Long accountId, String stockCode);

    /**
     * 계좌별 보유 종목 수 조회
     */
    long countByAccountId(Long accountId);
}
