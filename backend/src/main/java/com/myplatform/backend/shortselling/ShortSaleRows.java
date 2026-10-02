package com.myplatform.backend.shortselling;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * KIS 공매도 응답 → 행 (순수 함수, 2026-10-02).
 *
 * <p>필드명은 KIS 공식 샘플의 COLUMN_MAPPING 그대로다(koreainvestment/open-trading-api):
 * <ul>
 *   <li>공매도 상위종목[국내주식-133] {@code examples_llm/domestic_stock/short_sale/chk_short_sale.py}</li>
 *   <li>공매도 일별추이[국내주식-134] {@code examples_llm/domestic_stock/daily_short_sale/chk_daily_short_sale.py}</li>
 * </ul>
 * 둘 다 <b>거래 비중</b>(그날 거래량 중 공매도 몫)이지 잔고가 아니다. 빈값·'-'·숫자 아님은 null(모름) —
 * 실측 0 과 구분한다(§4c). 새 필드를 읽기 전엔 위 COLUMN_MAPPING 부터 볼 것(이름을 추측하지 않는다).
 */
public final class ShortSaleRows {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private ShortSaleRows() {
    }

    /** 공매도 상위종목 한 행. */
    public record RankingRow(String stockCode, String stockName, LocalDate tradeDate, int rank,
                             Long shortVolume, BigDecimal shortVolumeShare,
                             BigDecimal shortAmount, BigDecimal shortAmountShare,
                             Long totalVolume, BigDecimal totalAmount,
                             BigDecimal price, BigDecimal changeRate, BigDecimal avgPrice) {
    }

    /** 공매도 일별추이 한 행. */
    public record DailyRow(LocalDate date, Long shortVolume, BigDecimal shortVolumeShare,
                           BigDecimal shortAmount, BigDecimal shortAmountShare, BigDecimal closePrice) {
    }

    /**
     * 상위종목 {@code output} 배열 → 행. 순위는 {@code rankOffset + 1} 부터(연속조회 페이지를 이어 센다).
     * 종목코드나 기준일이 없는 행은 버린다 — 어디에 놓을지 모르는 값은 저장하지 않는다.
     */
    public static List<RankingRow> parseRanking(JsonNode output, int rankOffset) {
        List<RankingRow> rows = new ArrayList<>();
        if (output == null || !output.isArray()) {
            return rows;
        }
        int index = 0;
        for (JsonNode n : output) {
            index++;
            String code = text(n, "mksc_shrn_iscd");
            LocalDate date = date(n, "stnd_date2");
            if (date == null) {
                date = date(n, "stnd_date1");
            }
            if (code == null || date == null) {
                continue;
            }
            rows.add(new RankingRow(code, text(n, "hts_kor_isnm"), date, rankOffset + index,
                    integer(n, "ssts_cntg_qty"), decimal(n, "ssts_vol_rlim"),
                    decimal(n, "ssts_tr_pbmn"), decimal(n, "ssts_tr_pbmn_rlim"),
                    integer(n, "acml_vol"), decimal(n, "acml_tr_pbmn"),
                    decimal(n, "stck_prpr"), decimal(n, "prdy_ctrt"), decimal(n, "avrg_prc")));
        }
        return rows;
    }

    /** 일별추이 {@code output2} 배열 → 행. 영업일자가 없는 행은 버린다. */
    public static List<DailyRow> parseDaily(JsonNode output2) {
        List<DailyRow> rows = new ArrayList<>();
        if (output2 == null || !output2.isArray()) {
            return rows;
        }
        for (JsonNode n : output2) {
            LocalDate d = date(n, "stck_bsop_date");
            if (d == null) {
                continue;
            }
            rows.add(new DailyRow(d, integer(n, "ssts_cntg_qty"), decimal(n, "ssts_vol_rlim"),
                    decimal(n, "ssts_tr_pbmn"), decimal(n, "ssts_tr_pbmn_rlim"), decimal(n, "stck_clpr")));
        }
        return rows;
    }

    /**
     * {@code day} 이하에서 거래량 비중이 있는 가장 최근 행 — 장중 '오늘' 형성 행을 마감값처럼 쓰지 않고,
     * 값이 비어 있는 날을 0% 로 보이지 않게 건너뛴다.
     */
    public static Optional<DailyRow> latestOnOrBefore(List<DailyRow> rows, LocalDate day) {
        if (rows == null || day == null) {
            return Optional.empty();
        }
        return rows.stream()
                .filter(r -> !r.date().isAfter(day))
                .filter(r -> r.shortVolumeShare() != null)
                .max(Comparator.comparing(DailyRow::date));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    static BigDecimal decimal(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null || "-".equals(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long integer(JsonNode n, String field) {
        BigDecimal d = decimal(n, field);
        if (d == null) {
            return null;
        }
        try {
            return d.longValueExact();
        } catch (ArithmeticException e) {
            return null;   // 소수·범위 밖 — 수량으로 볼 수 없다
        }
    }

    static LocalDate date(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null || s.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(s, YMD);
        } catch (Exception e) {
            return null;
        }
    }
}
