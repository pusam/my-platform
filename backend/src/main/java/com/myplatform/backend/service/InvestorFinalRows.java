package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 종목별 투자자매매동향(일별) FHPTJ04160001 해석과 확정치 순위 — 순수 함수(2026-11-05).
 *
 * <p>필드는 공식 샘플 {@code chk_investor_trade_by_stock_daily.py} 의 COLUMN_MAPPING 그대로다: 날짜별 행은 {@code output2},
 * {@code stck_bsop_date}(영업 일자)·{@code stck_clpr}(종가)·{@code prdy_ctrt}(등락률)·{@code acml_vol}(거래량)·
 * {@code frgn_ntby_tr_pbmn}(외국인)·{@code orgn_ntby_tr_pbmn}(기관계)·{@code fund_ntby_tr_pbmn}(기금) 순매수 거래 대금.
 *
 * <p><b>금액 단위는 샘플에 없다</b> — 짐작하지 않는다. 프로그램매매({@link ProgramTradeRows})와 같이 같은 행의 거래 대금 ÷
 * (거래량 × 종가)로 판정한다: ≈1 이면 원, ≈1e-6 이면 백만원. 그 밖이면 그 응답은 쓰지 않는다(§4c).
 */
final class InvestorFinalRows {

    private InvestorFinalRows() {}

    /** 하루치 — 금액은 억원, 모르면 null. */
    record Row(LocalDate tradeDate, BigDecimal foreignNet, BigDecimal institutionNet, BigDecimal pensionNet,
               BigDecimal close, BigDecimal changeRate, Long volume) {}

    /**
     * @param rows      날짜별 행 — 단위를 모르면 빈 목록
     * @param unitRatio 단위 판정에 쓴 비율 — 판정할 행이 없으면 null
     * @param unitKnown 단위를 알았는가 — 거래가 한 건도 없는 응답은 모든 금액이 0 이라 어느 단위로도 같아 true
     */
    record Parsed(List<Row> rows, Double unitRatio, boolean unitKnown) {}

    private static final BigDecimal EOK = new BigDecimal("100000000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 단위 판정에 쓰는 (거래 대금, 거래량) 짝 — 매수 쪽이 먼저(평균 단가가 종가에 가깝다), 없으면 순매수. */
    private static final String[][] UNIT_PAIRS = {
            {"frgn_shnu_tr_pbmn", "frgn_shnu_vol"},
            {"orgn_shnu_tr_pbmn", "orgn_shnu_vol"},
            {"frgn_ntby_tr_pbmn", "frgn_ntby_qty"},
            {"orgn_ntby_tr_pbmn", "orgn_ntby_qty"},
    };

    static Parsed parse(JsonNode body) {
        JsonNode output = body == null ? null : body.get("output2");
        if (output == null || !output.isArray() || output.isEmpty()) return new Parsed(List.of(), null, true);

        List<JsonNode> nodes = new ArrayList<>();
        output.forEach(nodes::add);
        Double ratio = unitRatio(nodes);
        BigDecimal wonPerUnit = ratio == null ? BigDecimal.ONE
                : (ratio >= 0.5 && ratio <= 2.0) ? BigDecimal.ONE
                : (ratio >= 0.5e-6 && ratio <= 2.0e-6) ? MILLION
                : null;
        if (wonPerUnit == null) return new Parsed(List.of(), ratio, false);

        List<Row> rows = new ArrayList<>();
        for (JsonNode n : nodes) {
            LocalDate date = parseDate(text(n, "stck_bsop_date"));
            if (date == null) continue;
            String volume = text(n, "acml_vol");
            rows.add(new Row(date,
                    eok(decimal(n, "frgn_ntby_tr_pbmn"), wonPerUnit),
                    eok(decimal(n, "orgn_ntby_tr_pbmn"), wonPerUnit),
                    eok(decimal(n, "fund_ntby_tr_pbmn"), wonPerUnit),
                    decimal(n, "stck_clpr"),
                    decimal(n, "prdy_ctrt"),
                    volume == null ? null : parseLong(volume)));
        }
        return new Parsed(rows, ratio, true);
    }

    /** 거래량이 가장 큰 행의 대금 ÷ (거래량 × 종가) — 판정할 행이 없으면 null(모든 금액이 0 인 응답). */
    static Double unitRatio(List<JsonNode> nodes) {
        for (String[] pair : UNIT_PAIRS) {
            JsonNode best = null;
            BigDecimal bestVol = BigDecimal.ZERO;
            for (JsonNode n : nodes) {
                BigDecimal amount = decimal(n, pair[0]);
                BigDecimal vol = decimal(n, pair[1]);
                BigDecimal close = decimal(n, "stck_clpr");
                if (amount == null || vol == null || close == null) continue;
                if (amount.signum() == 0 || vol.signum() == 0 || close.signum() <= 0) continue;
                if (vol.abs().compareTo(bestVol) > 0) {
                    best = n;
                    bestVol = vol.abs();
                }
            }
            if (best != null) {
                BigDecimal denom = bestVol.multiply(decimal(best, "stck_clpr"));
                return decimal(best, pair[0]).abs().divide(denom, 12, RoundingMode.HALF_UP).doubleValue();
            }
        }
        return null;
    }

    // ==================== 순위 ====================

    /**
     * 하루치 확정치 → {@code investor_daily_trade} 행. 투자자마다 순매수 상위·순매도 상위 {@code top} 개, 순위는 1부터, 순매도 행의
     * 금액은 음수(읽는 쪽 {@code StockAnalysisService.signedNet} 규칙). 금액이 0 이거나 없으면 그 쪽 순위에 넣지 않는다.
     * 시장 칸은 'KOSPI' 로 통합 저장한다 — KOSDAQ 이 섞여 있는 것까지 종전 순위 행과 같다.
     */
    static List<InvestorDailyTrade> rank(LocalDate tradeDate, List<InvestorStockDaily> rows,
                                         Function<String, String> nameOf, int top) {
        List<InvestorDailyTrade> out = new ArrayList<>();
        addSide(out, tradeDate, rows, "FOREIGN", InvestorStockDaily::getForeignNet, nameOf, top);
        addSide(out, tradeDate, rows, "INSTITUTION", InvestorStockDaily::getInstitutionNet, nameOf, top);
        addSide(out, tradeDate, rows, "PENSION", InvestorStockDaily::getPensionNet, nameOf, top);
        return out;
    }

    private static void addSide(List<InvestorDailyTrade> out, LocalDate tradeDate, List<InvestorStockDaily> rows,
                                String investorType, Function<InvestorStockDaily, BigDecimal> netOf,
                                Function<String, String> nameOf, int top) {
        Comparator<InvestorStockDaily> byAmount = Comparator.comparing(netOf).thenComparing(InvestorStockDaily::getStockCode);
        List<InvestorStockDaily> buys = new ArrayList<>();
        List<InvestorStockDaily> sells = new ArrayList<>();
        for (InvestorStockDaily r : rows) {
            BigDecimal net = netOf.apply(r);
            if (net == null || net.signum() == 0) continue;
            (net.signum() > 0 ? buys : sells).add(r);
        }
        buys.sort(Comparator.comparing(netOf, Comparator.reverseOrder()).thenComparing(InvestorStockDaily::getStockCode));
        sells.sort(byAmount);
        addRows(out, tradeDate, buys, investorType, "BUY", netOf, nameOf, top);
        addRows(out, tradeDate, sells, investorType, "SELL", netOf, nameOf, top);
    }

    private static void addRows(List<InvestorDailyTrade> out, LocalDate tradeDate, List<InvestorStockDaily> sorted,
                                String investorType, String tradeType, Function<InvestorStockDaily, BigDecimal> netOf,
                                Function<String, String> nameOf, int top) {
        int rank = 1;
        for (InvestorStockDaily r : sorted) {
            if (rank > top) break;
            BigDecimal net = netOf.apply(r);
            String name = nameOf.apply(r.getStockCode());
            out.add(InvestorDailyTrade.builder()
                    .marketType("KOSPI")
                    .tradeDate(tradeDate)
                    .investorType(investorType)
                    .tradeType(tradeType)
                    .rankNum(rank++)
                    .stockCode(r.getStockCode())
                    .stockName(name == null || name.isBlank() ? r.getStockCode() : name)
                    .netBuyAmount(net)
                    .buyAmount(net.signum() > 0 ? net : BigDecimal.ZERO)
                    .sellAmount(net.signum() < 0 ? net.abs() : BigDecimal.ZERO)
                    .currentPrice(r.getClosePrice())
                    .changeRate(r.getChangeRate())
                    .tradeVolume(r.getVolume())
                    .dataBasis(InvestorDailyTrade.BASIS_FINAL)
                    .build());
        }
    }

    // ==================== 파싱 ====================

    private static BigDecimal eok(BigDecimal amount, BigDecimal wonPerUnit) {
        return amount == null ? null : amount.multiply(wonPerUnit).divide(EOK, 2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, YMD);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.valueOf(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
