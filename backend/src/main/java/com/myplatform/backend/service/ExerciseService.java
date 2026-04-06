package com.myplatform.backend.service;

import com.myplatform.backend.dto.ExerciseDto;
import com.myplatform.backend.dto.ExerciseRequest;
import com.myplatform.backend.entity.ExerciseRecord;
import com.myplatform.backend.repository.ExerciseRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ExerciseService {

    private final ExerciseRepository exerciseRepository;
    private final UserRepository userRepository;

    public ExerciseService(ExerciseRepository exerciseRepository, UserRepository userRepository) {
        this.exerciseRepository = exerciseRepository;
        this.userRepository = userRepository;
    }

    private Long getUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."))
                .getId();
    }

    public List<ExerciseDto> getRecords(String username) {
        Long userId = getUserId(username);
        return exerciseRepository.findByUserIdOrderByExerciseDateDesc(userId)
                .stream().map(ExerciseDto::fromEntity).collect(Collectors.toList());
    }

    public List<ExerciseDto> getByType(String username, String type) {
        Long userId = getUserId(username);
        return exerciseRepository.findByUserIdAndExerciseTypeOrderByExerciseDateDesc(userId, type)
                .stream().map(ExerciseDto::fromEntity).collect(Collectors.toList());
    }

    public List<ExerciseDto> getByDateRange(String username, LocalDate from, LocalDate to) {
        Long userId = getUserId(username);
        return exerciseRepository.findByUserIdAndExerciseDateBetweenOrderByExerciseDateDesc(userId, from, to)
                .stream().map(ExerciseDto::fromEntity).collect(Collectors.toList());
    }

    @Transactional
    public ExerciseDto add(String username, ExerciseRequest request) {
        Long userId = getUserId(username);
        ExerciseRecord record = new ExerciseRecord();
        record.setUserId(userId);
        record.setExerciseType(request.getExerciseType());
        record.setExerciseName(request.getExerciseName());
        record.setDurationMinutes(request.getDurationMinutes());
        record.setSets(request.getSets());
        record.setReps(request.getReps());
        record.setWeight(request.getWeight());
        record.setIntensity(request.getIntensity() != null ? request.getIntensity() : "MEDIUM");
        record.setCaloriesBurned(request.getCaloriesBurned());
        record.setExerciseDate(request.getExerciseDate() != null ? request.getExerciseDate() : LocalDate.now());
        record.setMemo(request.getMemo());
        return ExerciseDto.fromEntity(exerciseRepository.save(record));
    }

    @Transactional
    public ExerciseDto update(String username, Long id, ExerciseRequest request) {
        Long userId = getUserId(username);
        ExerciseRecord record = exerciseRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("운동 기록을 찾을 수 없습니다."));
        record.setExerciseType(request.getExerciseType());
        record.setExerciseName(request.getExerciseName());
        record.setDurationMinutes(request.getDurationMinutes());
        record.setSets(request.getSets());
        record.setReps(request.getReps());
        record.setWeight(request.getWeight());
        if (request.getIntensity() != null) record.setIntensity(request.getIntensity());
        record.setCaloriesBurned(request.getCaloriesBurned());
        if (request.getExerciseDate() != null) record.setExerciseDate(request.getExerciseDate());
        record.setMemo(request.getMemo());
        return ExerciseDto.fromEntity(exerciseRepository.save(record));
    }

    @Transactional
    public void delete(String username, Long id) {
        Long userId = getUserId(username);
        ExerciseRecord record = exerciseRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("운동 기록을 찾을 수 없습니다."));
        exerciseRepository.delete(record);
    }

    public Map<String, Object> getSummary(String username) {
        Long userId = getUserId(username);
        List<ExerciseRecord> all = exerciseRepository.findByUserIdOrderByExerciseDateDesc(userId);
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRecords", all.size());
        summary.put("todayMinutes", all.stream()
                .filter(r -> LocalDate.now().equals(r.getExerciseDate()))
                .mapToInt(r -> r.getDurationMinutes() != null ? r.getDurationMinutes() : 0).sum());
        summary.put("todayCaloriesBurned", all.stream()
                .filter(r -> LocalDate.now().equals(r.getExerciseDate()))
                .mapToInt(r -> r.getCaloriesBurned() != null ? r.getCaloriesBurned() : 0).sum());
        summary.put("todayExercises", all.stream()
                .filter(r -> LocalDate.now().equals(r.getExerciseDate())).count());
        return summary;
    }
}
