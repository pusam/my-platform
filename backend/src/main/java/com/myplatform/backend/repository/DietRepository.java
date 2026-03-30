package com.myplatform.backend.repository;

import com.myplatform.backend.entity.DietRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DietRepository extends JpaRepository<DietRecord, Long> {

    List<DietRecord> findByUserIdOrderByMealDateDesc(Long userId);

    List<DietRecord> findByUserIdAndDietTypeOrderByMealDateDesc(Long userId, String dietType);

    List<DietRecord> findByUserIdAndMealDateBetweenOrderByMealDateDesc(Long userId, LocalDate from, LocalDate to);

    Optional<DietRecord> findByIdAndUserId(Long id, Long userId);
}
