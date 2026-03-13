package com.myplatform.backend.repository;

import com.myplatform.backend.entity.EarningsDisclosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EarningsDisclosureRepository extends JpaRepository<EarningsDisclosure, Long> {

    Optional<EarningsDisclosure> findByRceptNo(String rceptNo);

    boolean existsByRceptNo(String rceptNo);

    List<EarningsDisclosure> findByRceptDtBetweenOrderByRceptDtDesc(String startDate, String endDate);

    List<EarningsDisclosure> findByCorpNameContainingOrderByRceptDtDesc(String corpName);

    List<EarningsDisclosure> findByStockCodeOrderByRceptDtDesc(String stockCode);

    List<EarningsDisclosure> findByDisclosureTypeOrderByRceptDtDesc(String disclosureType);

    @Query("SELECT e FROM EarningsDisclosure e WHERE e.stockCode IN :stockCodes ORDER BY e.rceptDt DESC")
    List<EarningsDisclosure> findByStockCodeInOrderByRceptDtDesc(@Param("stockCodes") List<String> stockCodes);

    @Query("SELECT e FROM EarningsDisclosure e WHERE e.rceptDt >= :startDate ORDER BY e.rceptDt DESC")
    List<EarningsDisclosure> findRecentDisclosures(@Param("startDate") String startDate);

    @Query("SELECT DISTINCT e.corpName FROM EarningsDisclosure e WHERE e.rceptDt >= :startDate ORDER BY e.corpName")
    List<String> findDistinctCorpNamesSince(@Param("startDate") String startDate);
}
