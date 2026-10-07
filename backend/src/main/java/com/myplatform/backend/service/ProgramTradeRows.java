package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ScalpingAnalysisDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 종목별 프로그램매매추이(체결)[국내주식-044, FHPPG04650101] 응답 해석 — 순수 함수(2026-10-07).
 *
 * <p>필드는 공식 샘플 {@code chk_program_trade_by_stock.py} 의 COLUMN_MAPPING 그대로: 행은 {@code output} 배열이고
 * {@code bsop_hour}(영업 시간 HHMMSS)·{@code stck_prpr}(현재가)·{@code whol_smtn_shnu_vol}(전체 합계 매수 거래량)·
 * {@code whol_smtn_shnu_tr_pbmn}(매수 거래대금)·{@code whol_smtn_ntby_tr_pbmn}(순매수 거래대금 — 그 시각까지 누적)을 쓴다.
 *
 * <p><b>금액 단위는 샘플에 없다</b> — 다른 구현들도 원·백만원으로 갈린다. 짐작하지 않고 같은 행의 매수 거래대금 ÷ (매수 거래량 ×
 * 현재가)로 판정한다: ≈1 이면 원, ≈1e-6 이면 백만원. 그 밖이면 모른다 — 값을 만들지 않는다(§4c, 화면 '-').
 */
final class ProgramTradeRows {

    private ProgramTradeRows() {}

    /**
     * @param netBuyEok 당일 누적 프로그램 순매수(억, 최신 시각 행) — 모르면 null
     * @param series    시각별 누적 순매수(억, 오래된 것부터) — 모르면 빈 목록
     * @param unitRatio 단위 판정에 쓴 비율(매수 거래대금 ÷ (매수 거래량 × 현재가)) — 판정할 행이 없으면 null
     */
    record Parsed(BigDecimal netBuyEok, List<ScalpingAnalysisDto.ProgramTradingPoint> series, Double unitRatio) {}

    private static final BigDecimal EOK = new BigDecimal("100000000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    static Parsed parse(JsonNode body) {
        JsonNode output = body == null ? null : body.get("output");
        if (output == null || !output.isArray() || output.isEmpty()) return new Parsed(null, List.of(), null);

        List<JsonNode> rows = new ArrayList<>();
        output.forEach(rows::add);
        rows.sort(Comparator.comparing(r -> text(r, "bsop_hour") == null ? "" : text(r, "bsop_hour")));
        JsonNode latest = rows.get(rows.size() - 1);
        BigDecimal latestNet = decimal(latest, "whol_smtn_ntby_tr_pbmn");

        Double ratio = unitRatio(rows);
        BigDecimal wonPerUnit = ratio == null ? null
                : (ratio >= 0.5 && ratio <= 2.0) ? BigDecimal.ONE
                : (ratio >= 0.5e-6 && ratio <= 2.0e-6) ? MILLION
                : null;
        if (wonPerUnit == null) {
            // 단위를 모르면 값을 만들지 않는다 — 다만 프로그램 매수가 아직 없고 순매수가 0 이면 어느 단위로도 0 이다
            boolean zero = ratio == null && latestNet != null && latestNet.signum() == 0;
            return new Parsed(zero ? BigDecimal.ZERO : null, List.of(), ratio);
        }

        List<ScalpingAnalysisDto.ProgramTradingPoint> series = new ArrayList<>();
        for (JsonNode r : rows) {
            String hour = text(r, "bsop_hour");
            BigDecimal net = decimal(r, "whol_smtn_ntby_tr_pbmn");
            if (hour == null || hour.length() < 4 || net == null) continue;
            series.add(ScalpingAnalysisDto.ProgramTradingPoint.builder()
                    .time(hour.substring(0, 2) + ":" + hour.substring(2, 4))
                    .netBuyAmount(toEok(net, wonPerUnit))
                    .build());
        }
        return new Parsed(latestNet == null ? null : toEok(latestNet, wonPerUnit), series, ratio);
    }

    /** 매수 거래량이 가장 큰 행의 매수 거래대금 ÷ (매수 거래량 × 현재가) — 셋 다 양수인 행이 없으면 null. */
    private static Double unitRatio(List<JsonNode> rows) {
        JsonNode best = null;
        BigDecimal bestVol = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            BigDecimal vol = decimal(r, "whol_smtn_shnu_vol");
            BigDecimal amt = decimal(r, "whol_smtn_shnu_tr_pbmn");
            BigDecimal px = decimal(r, "stck_prpr");
            if (vol == null || amt == null || px == null || vol.signum() <= 0 || amt.signum() <= 0 || px.signum() <= 0) continue;
            if (vol.compareTo(bestVol) > 0) { best = r; bestVol = vol; }
        }
        if (best == null) return null;
        BigDecimal denom = bestVol.multiply(decimal(best, "stck_prpr"));
        return decimal(best, "whol_smtn_shnu_tr_pbmn").divide(denom, 12, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal toEok(BigDecimal amount, BigDecimal wonPerUnit) {
        return amount.multiply(wonPerUnit).divide(EOK, 2, RoundingMode.HALF_UP);
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
