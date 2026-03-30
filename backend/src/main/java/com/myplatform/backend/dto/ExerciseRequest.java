package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class ExerciseRequest {

    private String exerciseType;
    private String exerciseName;
    private Integer durationMinutes;
    private Integer sets;
    private Integer reps;
    private BigDecimal weight;
    private String intensity;
    private Integer caloriesBurned;
    private LocalDate exerciseDate;
    private String memo;

    // Getters & Setters
    public String getExerciseType() { return exerciseType; }
    public void setExerciseType(String exerciseType) { this.exerciseType = exerciseType; }
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
    public Integer getCaloriesBurned() { return caloriesBurned; }
    public void setCaloriesBurned(Integer caloriesBurned) { this.caloriesBurned = caloriesBurned; }
    public LocalDate getExerciseDate() { return exerciseDate; }
    public void setExerciseDate(LocalDate exerciseDate) { this.exerciseDate = exerciseDate; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
}
