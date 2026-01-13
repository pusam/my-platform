package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SilverPrice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SilverPriceRepository extends JpaRepository<SilverPrice, Long> {

    // 가장 최신 데이터 조회
    Optional<SilverPrice> findTopByOrderByFetchedAtDesc();

    // 히스토리 조회 (최신순)
    Page<SilverPrice> findAllByOrderByFetchedAtDesc(Pageable pageable);

    // 특정 기간 히스토리 조회
    List<SilverPrice> findByFetchedAtBetweenOrderByFetchedAtDesc(LocalDateTime start, LocalDateTime end);

    // 특정 날짜 데이터 조회
    List<SilverPrice> findByBaseDateOrderByFetchedAtDesc(String baseDate);
}
