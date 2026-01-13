package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "자산 등록 요청")
public class AssetRequest {

    @Schema(description = "자산 유형 (GOLD, SILVER)", example = "GOLD")
    private String assetType;

    @Schema(description = "보유량 (그램)", example = "10.5")
    private BigDecimal quantity;

    @Schema(description = "구매 당시 그램당 가격", example = "75000")
    private BigDecimal purchasePrice;

    @Schema(description = "구매일", example = "2024-01-15")
    private LocalDate purchaseDate;

    @Schema(description = "메모", example = "결혼반지")
    private String memo;

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
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

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}

