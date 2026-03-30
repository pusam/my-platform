package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class DietRequest {

    private String dietType;
    private String foodName;
    private Integer calories;
    private BigDecimal protein;
    private BigDecimal carbs;
    private BigDecimal fat;
    private String portion;
    private LocalDate mealDate;
    private String memo;

    // Getters & Setters
    public String getDietType() { return dietType; }
    public void setDietType(String dietType) { this.dietType = dietType; }
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
}
