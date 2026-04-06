package com.myplatform.backend.repository;

import com.myplatform.backend.entity.ExerciseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExerciseRepository extends JpaRepository<ExerciseRecord, Long> {

    List<ExerciseRecord> findByUserIdOrderByExerciseDateDesc(Long userId);

    List<ExerciseRecord> findByUserIdAndExerciseTypeOrderByExerciseDateDesc(Long userId, String exerciseType);

    List<ExerciseRecord> findByUserIdAndExerciseDateBetweenOrderByExerciseDateDesc(Long userId, LocalDate from, LocalDate to);

    Optional<ExerciseRecord> findByIdAndUserId(Long id, Long userId);
}
