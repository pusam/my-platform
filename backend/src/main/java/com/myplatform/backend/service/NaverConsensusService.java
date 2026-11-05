package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목 상세의 배당수익률·증권사 목표주가 — 네이버 증권 종목 응답(표시 전용, 산식·봇 미편입).
 * 읽던 네이버 메인 페이지(finance.naver.com/item/main.naver)가 stock.naver.com 으로 옮겨 값이 사라졌는데, 화면이 값이 없으면 카드를 숨겨서
 * 아무도 몰랐다(2026-11-05 확인, 재무 표의 dividend_yield 도 한 행도 안 채워져 있다). 모르면 null — 화면이 숨긴다(§4c).
 * 실패는 캐시하지 않는다.
 */
@Service
@Slf4j
public class NaverConsensusService {

    private static final String INTEGRATION_URL = "https://m.stock.naver.com/api/stock/%s/integration";
    private static final long TTL_MS = 60 * 60 * 1000L;
    private static final BigDecimal MAX_YIELD = new BigDecimal("30");      // 30% 이상은 파싱 오류로 본다(종전 규칙)
    private static final BigDecimal MIN_TARGET = new BigDecimal("1000");
    private static final BigDecimal MAX_TARGET = new BigDecimal("100000000");
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("MM/dd");

    /** 배당수익률(%)·목표주가(원)·목표주가 기준일 — 각각 모르면 null. */
    public record Snapshot(BigDecimal dividendYield, BigDecimal targetPrice, LocalDate asOf) {
        public String sourceLabel() {
            return asOf == null ? "네이버 증권 컨센서스" : "네이버 증권 컨센서스 · " + asOf.format(LABEL_DATE) + " 기준";
        }
    }

    private record Cached(Snapshot snapshot, long at) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public NaverConsensusService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 실패면 null. */
    public Snapshot fetch(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;
        long now = System.currentTimeMillis();
        Cached cached = cache.get(stockCode);
        if (cached != null && now - cached.at() < TTL_MS) return cached.snapshot();
        try {
            String body = restTemplate.getForObject(String.format(INTEGRATION_URL, stockCode), String.class);
            if (body == null) return null;
            Snapshot snapshot = parse(objectMapper.readTree(body));
            cache.put(stockCode, new Cached(snapshot, now));
            return snapshot;
        } catch (Exception e) {
            log.warn("[네이버 컨센서스] {} 조회 실패: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /** 순수 — 응답 한 건. */
    static Snapshot parse(JsonNode root) {
        BigDecimal yield = null;
        for (JsonNode info : root.path("totalInfos")) {
            if ("dividendYieldRatio".equals(info.path("code").asText())) {
                yield = number(info.path("value").asText(""));
                break;
            }
        }
        if (yield != null && (yield.signum() <= 0 || yield.compareTo(MAX_YIELD) >= 0)) yield = null;

        JsonNode consensus = root.path("consensusInfo");
        BigDecimal target = number(consensus.path("priceTargetMean").asText(""));
        if (target != null && (target.compareTo(MIN_TARGET) < 0 || target.compareTo(MAX_TARGET) >= 0)) target = null;

        return new Snapshot(yield, target, target == null ? null : date(consensus.path("createDate").asText("")));
    }

    /** "472,381" · "0.83%" → 숫자. "N/A"·"-"·빈 값은 null. */
    private static BigDecimal number(String text) {
        String cleaned = text.replace(",", "").replace("%", "").trim();
        if (cleaned.isEmpty()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String text) {
        try {
            return text.isBlank() ? null : LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
