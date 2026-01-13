package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "자산 정보")
public class AssetDto {

    @Schema(description = "자산 ID")
    private Long id;

    @Schema(description = "자산 유형 (GOLD, SILVER, STOCK)")
    private String assetType;

    @Schema(description = "종목코드 (주식인 경우)")
    private String stockCode;

    @Schema(description = "종목명 (주식인 경우)")
    private String stockName;

    @Schema(description = "보유량 (그램 또는 주)")
    private BigDecimal quantity;

    @Schema(description = "구매 당시 그램당 가격")
    private BigDecimal purchasePrice;

    @Schema(description = "구매일")
    private LocalDate purchaseDate;

    @Schema(description = "총 구매금액")
    private BigDecimal totalAmount;

    @Schema(description = "메모")
    private String memo;

    @Schema(description = "등록일시")
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

