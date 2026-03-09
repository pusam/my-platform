package com.myplatform.backend.repository;

import com.myplatform.backend.entity.OilPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OilPriceRepository extends JpaRepository<OilPrice, Long> {

    Optional<OilPrice> findTopByOrderByFetchedAtDesc();

    List<OilPrice> findByFetchedAtAfterOrderByFetchedAtAsc(LocalDateTime start);
}
