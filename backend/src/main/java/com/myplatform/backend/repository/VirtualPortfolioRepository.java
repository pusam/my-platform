package com.myplatform.backend.repository;

import com.myplatform.backend.entity.VirtualPortfolio;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 계좌 + 종목코드로 삭제
     */
    void deleteByAccountIdAndStockCode(Long accountId, String stockCode);

    /**
     * 계좌별 보유 종목 수 조회
     */
    long countByAccountId(Long accountId);
}
