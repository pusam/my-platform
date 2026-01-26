package com.myplatform.backend.dto;

import com.myplatform.backend.entity.CarRecord;
import java.math.BigDecimal;
import java.time.LocalDate;

public class CarRecordDto {

    private Long id;
    private String carName;
    private String plateNumber;
    private String recordType;
    private LocalDate recordDate;
    private Integer mileage;
    private Integer nextMileage;
    private BigDecimal cost;
    private String shop;
    private String memo;

    // 정비 유형 한글명
    private String recordTypeName;

    public CarRecordDto() {}

    public static CarRecordDto fromEntity(CarRecord entity) {
        CarRecordDto dto = new CarRecordDto();
        dto.setId(entity.getId());
        dto.setCarName(entity.getCarName());
        dto.setPlateNumber(entity.getPlateNumber());
        dto.setRecordType(entity.getRecordType());
        dto.setRecordTypeName(getRecordTypeName(entity.getRecordType()));
        dto.setRecordDate(entity.getRecordDate());
        dto.setMileage(entity.getMileage());
        dto.setNextMileage(entity.getNextMileage());
        dto.setCost(entity.getCost());
        dto.setShop(entity.getShop());
        dto.setMemo(entity.getMemo());
        return dto;
    }

    private static String getRecordTypeName(String recordType) {
        if (recordType == null) return "";
        return switch (recordType) {
            case "ENGINE_OIL" -> "엔진오일";
            case "TIRE" -> "타이어";
            case "BRAKE" -> "브레이크";
            case "FILTER" -> "필터";
            case "BATTERY" -> "배터리";
            case "INSPECTION" -> "정기점검";
            case "WIPER" -> "와이퍼";
            case "COOLANT" -> "냉각수";
            case "TRANSMISSION" -> "미션오일";
            case "OTHER" -> "기타";
            default -> recordType;
        };
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCarName() {
        return carName;
    }

    public void setCarName(String carName) {
        this.carName = carName;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getRecordTypeName() {
        return recordTypeName;
    }

    public void setRecordTypeName(String recordTypeName) {
        this.recordTypeName = recordTypeName;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(LocalDate recordDate) {
        this.recordDate = recordDate;
    }

    public Integer getMileage() {
        return mileage;
    }

    public void setMileage(Integer mileage) {
        this.mileage = mileage;
    }

    public Integer getNextMileage() {
        return nextMileage;
    }

    public void setNextMileage(Integer nextMileage) {
        this.nextMileage = nextMileage;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public String getShop() {
        return shop;
    }

    public void setShop(String shop) {
        this.shop = shop;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
