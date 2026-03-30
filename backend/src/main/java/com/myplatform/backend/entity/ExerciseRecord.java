package com.myplatform.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercise_record")
public class ExerciseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "exercise_type", nullable = false, length = 30)
    private String exerciseType;

    @Column(name = "exercise_name", nullable = false, length = 100)
    private String exerciseName;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "sets")
    private Integer sets;

    @Column(name = "reps")
    private Integer reps;

    @Column(name = "weight", precision = 6, scale = 1)
    private BigDecimal weight;

    @Column(name = "intensity", length = 20)
    private String intensity;

    @Column(name = "calories_burned")
    private Integer caloriesBurned;

    @Column(name = "exercise_date", nullable = false)
    private LocalDate exerciseDate;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
