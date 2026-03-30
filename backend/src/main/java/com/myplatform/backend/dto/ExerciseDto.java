package com.myplatform.backend.dto;

import com.myplatform.backend.entity.ExerciseRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public class ExerciseDto {

    private Long id;
    private String exerciseType;
    private String exerciseTypeName;
    private String exerciseName;
    private Integer durationMinutes;
    private Integer sets;
    private Integer reps;
    private BigDecimal weight;
    private String intensity;
    private String intensityName;
    private Integer caloriesBurned;
    private LocalDate exerciseDate;
    private String memo;
    private LocalDateTime createdAt;

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "CARDIO", "유산소",
            "STRENGTH", "근력",
            "FLEXIBILITY", "유연성",
            "SPORTS", "스포츠"
    );

    private static final Map<String, String> INTENSITY_NAMES = Map.of(
            "LOW", "낮음",
            "MEDIUM", "보통",
            "HIGH", "높음"
    );

    public static ExerciseDto fromEntity(ExerciseRecord entity) {
        ExerciseDto dto = new ExerciseDto();
        dto.setId(entity.getId());
        dto.setExerciseType(entity.getExerciseType());
        dto.setExerciseTypeName(TYPE_NAMES.getOrDefault(entity.getExerciseType(), entity.getExerciseType()));
        dto.setExerciseName(entity.getExerciseName());
        dto.setDurationMinutes(entity.getDurationMinutes());
        dto.setSets(entity.getSets());
        dto.setReps(entity.getReps());
        dto.setWeight(entity.getWeight());
        dto.setIntensity(entity.getIntensity());
        dto.setIntensityName(INTENSITY_NAMES.getOrDefault(entity.getIntensity(), entity.getIntensity()));
        dto.setCaloriesBurned(entity.getCaloriesBurned());
        dto.setExerciseDate(entity.getExerciseDate());
        dto.setMemo(entity.getMemo());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExerciseType() { return exerciseType; }
    public void setExerciseType(String exerciseType) { this.exerciseType = exerciseType; }
    public String getExerciseTypeName() { return exerciseTypeName; }
    public void setExerciseTypeName(String exerciseTypeName) { this.exerciseTypeName = exerciseTypeName; }
    public String getExerciseName() { return exerciseName; }
    public void setExerciseName(String exerciseName) { this.exerciseName = exerciseName; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public Integer getSets() { return sets; }
    public void setSets(Integer sets) { this.sets = sets; }
    public Integer getReps() { return reps; }
    public void setReps(Integer reps) { this.reps = reps; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public String getIntensity() { return intensity; }
    public void setIntensity(String intensity) { this.intensity = intensity; }
    public String getIntensityName() { return intensityName; }
    public void setIntensityName(String intensityName) { this.intensityName = intensityName; }
    public Integer getCaloriesBurned() { return caloriesBurned; }
    public void setCaloriesBurned(Integer caloriesBurned) { this.caloriesBurned = caloriesBurned; }
    public LocalDate getExerciseDate() { return exerciseDate; }
    public void setExerciseDate(LocalDate exerciseDate) { this.exerciseDate = exerciseDate; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
