package com.myplatform.backend.youtubeopinion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 모델 응답 텍스트 → 원시 발언 목록. 순수 함수.
 *
 * <p>JSON 배열로 읽을 수 없으면 {@code Optional.empty()} — 분석 실패로 다뤄 실행을 FAILED 로 남긴다.
 * "읽을 수 없음"을 "발언 없음(빈 배열)"과 섞지 않는다(§4c). 배열 안의 객체가 아닌 원소는 버리고 수만 센다.
 */
public final class OpinionResponseParser {

    private OpinionResponseParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record RawStatement(String stockName, String stockCode, String statementType, String stance,
                               String speaker, String conditions, String horizon, BigDecimal targetPrice,
                               String claimSummary, String rationale, Integer startCue, Integer endCue,
                               String evidenceQuote) {}

    public record Parsed(List<RawStatement> statements, int nonObjectItems) {}

    public static Optional<Parsed> parse(String reply) {
        if (reply == null || reply.isBlank()) return Optional.empty();
        int a = reply.indexOf('[');
        int b = reply.lastIndexOf(']');
        if (a < 0 || b < a) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(reply.substring(a, b + 1));
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isArray()) return Optional.empty();
        List<RawStatement> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode n : root) {
            if (!n.isObject()) { skipped++; continue; }
            out.add(new RawStatement(text(n, "stockName"), text(n, "stockCode"), text(n, "statementType"),
                    text(n, "stance"), text(n, "speaker"), text(n, "conditions"), text(n, "horizon"),
                    decimal(n, "targetPrice"), text(n, "claimSummary"), text(n, "rationale"),
                    integer(n, "startCue"), integer(n, "endCue"), text(n, "evidenceQuote")));
        }
        return Optional.of(new Parsed(out, skipped));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.isTextual() ? v.asText() : v.toString();
        s = s.trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asInt();
        if (v.isTextual()) {
            String s = v.asText().trim().replaceFirst("^[cC]", "");
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            String s = v.asText().replaceAll("[,\\s원]", "");
            try { return s.isEmpty() ? null : new BigDecimal(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
