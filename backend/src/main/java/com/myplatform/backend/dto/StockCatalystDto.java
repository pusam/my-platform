package com.myplatform.backend.dto;

import com.myplatform.backend.entity.StockCatalyst;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 재료 태그 응답 — V31. 프론트 배지(🔥 재료: 수주(호재)) 표시용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockCatalystDto {

    private String stockCode;
    /** ORDER_WIN/EARNINGS/MNA/NEW_BUSINESS/REGULATION/LITIGATION/GOVERNANCE/OTHER/NONE. */
    private String catalystType;
    /** 한국어 라벨 — 수주/실적/M&A/신사업/규제/소송/지배구조/기타/없음. */
    private String typeLabel;
    /** POSITIVE/NEGATIVE/NEUTRAL/NONE. */
    private String direction;
    private String headline;
    private String summary;
    /** 분류 근거 대표 뉴스 URL (V53) — null=미확보(프론트는 링크 생략, §4c). */
    private String newsLink;
    private LocalDate catalystDate;

    public static StockCatalystDto from(StockCatalyst c) {
        if (c == null) return null;
        return StockCatalystDto.builder()
                .stockCode(c.getStockCode())
                .catalystType(c.getCatalystType().name())
                .typeLabel(labelOf(c.getCatalystType()))
                .direction(c.getDirection().name())
                .headline(c.getHeadline())
                .summary(c.getSummary())
                .newsLink(c.getNewsLink())
                .catalystDate(c.getCatalystDate())
                .build();
    }

    public static String labelOf(StockCatalyst.CatalystType type) {
        return switch (type) {
            case ORDER_WIN -> "수주";
            case EARNINGS -> "실적";
            case MNA -> "M&A";
            case NEW_BUSINESS -> "신사업";
            case REGULATION -> "규제";
            case LITIGATION -> "소송";
            case GOVERNANCE -> "지배구조";
            case OTHER -> "기타";
            case NONE -> "없음";
        };
    }
}
