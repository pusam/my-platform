package com.myplatform.backend.dto;

import com.myplatform.backend.entity.DietRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public class DietDto {

    private Long id;
    private String dietType;
    private String dietTypeName;
    private String foodName;
    private Integer calories;
    private BigDecimal protein;
    private BigDecimal carbs;
    private BigDecimal fat;
    private String portion;
    private LocalDate mealDate;
    private String memo;
    private LocalDateTime createdAt;

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "BREAKFAST", "아침",
            "LUNCH", "점심",
            "DINNER", "저녁",
            "SNACK", "간식"
    );

    public static DietDto fromEntity(DietRecord entity) {
        DietDto dto = new DietDto();
        dto.setId(entity.getId());
        dto.setDietType(entity.getDietType());
        dto.setDietTypeName(TYPE_NAMES.getOrDefault(entity.getDietType(), entity.getDietType()));
        dto.setFoodName(entity.getFoodName());
        dto.setCalories(entity.getCalories());
        dto.setProtein(entity.getProtein());
        dto.setCarbs(entity.getCarbs());
        dto.setFat(entity.getFat());
        dto.setPortion(entity.getPortion());
        dto.setMealDate(entity.getMealDate());
        dto.setMemo(entity.getMemo());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDietType() { return dietType; }
    public void setDietType(String dietType) { this.dietType = dietType; }
    public String getDietTypeName() { return dietTypeName; }
    public void setDietTypeName(String dietTypeName) { this.dietTypeName = dietTypeName; }
    public String getFoodName() { return foodName; }
    public void setFoodName(String foodName) { this.foodName = foodName; }
    public Integer getCalories() { return calories; }
    public void setCalories(Integer calories) { this.calories = calories; }
    public BigDecimal getProtein() { return protein; }
    public void setProtein(BigDecimal protein) { this.protein = protein; }
    public BigDecimal getCarbs() { return carbs; }
    public void setCarbs(BigDecimal carbs) { this.carbs = carbs; }
    public BigDecimal getFat() { return fat; }
    public void setFat(BigDecimal fat) { this.fat = fat; }
    public String getPortion() { return portion; }
    public void setPortion(String portion) { this.portion = portion; }
    public LocalDate getMealDate() { return mealDate; }
    public void setMealDate(LocalDate mealDate) { this.mealDate = mealDate; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
