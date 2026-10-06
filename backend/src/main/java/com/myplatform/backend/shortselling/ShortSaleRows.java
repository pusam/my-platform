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

    /**
     * 상위종목 한 페이지의 파싱 결과 — 저장할 행과 버린 이유별 개수(2026-10-07). 수집 로그·상태가 이것으로 "왜 몇 건만 남았나"를 말한다.
     *
     * @param missingCode        종목코드가 없어 버린 행 수
     * @param missingDate        자기 기준일도 없고 응답 기준일도 정할 수 없어 버린 행 수
     * @param dateFromResponse   자기 기준일이 없어 응답 기준일을 쓴 행 수
     * @param firstDroppedFields 처음 버린 행의 필드 이름(값은 담지 않는다) — 응답 모양이 바뀌었는지 보는 단서
     */
    public record RankingParse(List<RankingRow> rows, int missingCode, int missingDate, int dateFromResponse,
                               List<String> firstDroppedFields) {
        public int dropped() {
            return missingCode + missingDate;
        }
    }

    /** 공매도 일별추이 한 행. */
    public record DailyRow(LocalDate date, Long shortVolume, BigDecimal shortVolumeShare,
                           BigDecimal shortAmount, BigDecimal shortAmountShare, BigDecimal closePrice) {
    }

    /** {@link #parseRankingDetailed} 의 행만. 순위는 {@code rankOffset + 1} 부터(연속조회 페이지를 이어 센다). */
    public static List<RankingRow> parseRanking(JsonNode output, int rankOffset) {
        return parseRankingDetailed(output, rankOffset).rows();
    }

    /**
     * 상위종목 {@code output} 배열 → 행 + 버린 이유. 순위는 응답 위치({@code rankOffset + 1} 부터 — 버린 행도 자리를 차지한다).
     *
     * <p><b>기준일은 응답 단위다(2026-10-07)</b>: 1일 순위라 모든 행이 같은 날인데, KIS 는 기준일({@code stnd_date1/2})을
     * <b>첫 행에만</b> 채워 준다 — 10/2·10/6 18:30 수집이 30행 중 첫 행만 남기고 29행을 버린 이유다(공식 샘플 COLUMN_MAPPING 은
     * 행 필드로 적혀 있지만 실측은 첫 행뿐). 그래서 자기 기준일이 없는 행은 <b>같은 응답에 기준일이 딱 하나만 있을 때</b> 그 날짜에
     * 놓는다. 응답에 기준일이 없거나 서로 다른 기준일이 섞이면 짐작하지 않고 버린다. 종목코드가 없는 행은 어디에 놓을지 몰라
     * 버린다(§4c).
     */
    public static RankingParse parseRankingDetailed(JsonNode output, int rankOffset) {
        List<RankingRow> rows = new ArrayList<>();
        if (output == null || !output.isArray()) {
            return new RankingParse(rows, 0, 0, 0, List.of());
        }
        java.util.Set<LocalDate> dates = new java.util.LinkedHashSet<>();
        for (JsonNode n : output) {
            LocalDate own = rowDate(n);
            if (own != null) dates.add(own);
        }
        LocalDate responseDate = dates.size() == 1 ? dates.iterator().next() : null;

        int missingCode = 0;
        int missingDate = 0;
        int fromResponse = 0;
        List<String> firstDropped = List.of();
        int index = 0;
        for (JsonNode n : output) {
            index++;
            String code = text(n, "mksc_shrn_iscd");
            LocalDate date = rowDate(n);
            if (code == null || (date == null && responseDate == null)) {
                if (code == null) missingCode++;
                else missingDate++;
                if (firstDropped.isEmpty()) firstDropped = fieldNames(n);
                continue;
            }
            if (date == null) {
                date = responseDate;
                fromResponse++;
            }
            rows.add(new RankingRow(code, text(n, "hts_kor_isnm"), date, rankOffset + index,
                    integer(n, "ssts_cntg_qty"), decimal(n, "ssts_vol_rlim"),
                    decimal(n, "ssts_tr_pbmn"), decimal(n, "ssts_tr_pbmn_rlim"),
                    integer(n, "acml_vol"), decimal(n, "acml_tr_pbmn"),
                    decimal(n, "stck_prpr"), decimal(n, "prdy_ctrt"), decimal(n, "avrg_prc")));
        }
        return new RankingParse(rows, missingCode, missingDate, fromResponse, firstDropped);
    }

    /** 행 자신의 기준일 — stnd_date2, 없으면 stnd_date1. */
    private static LocalDate rowDate(JsonNode n) {
        LocalDate date = date(n, "stnd_date2");
        return date != null ? date : date(n, "stnd_date1");
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
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
