package com.myplatform.backend.service;

import com.myplatform.backend.dto.ExchangeRateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 환율 정보 서비스
 * - 1순위: 한국수출입은행 Open API (안정적, 공식)
 * - 2순위: 네이버 금융 스크래핑 (폴백)
 * - 외국인 수급 신호 분석
 */
@Service
@Slf4j
public class ExchangeRateService {

    @Value("${koreaexim.api.key:}")
    private String koreaeximApiKey;

    @Value("${koreaexim.api.url:https://www.koreaexim.go.kr/site/program/financial/exchangeJSON}")
    private String koreaeximApiUrl;

    private static final String NAVER_EXCHANGE_URL =
            "https://finance.naver.com/marketindex/exchangeDetail.naver?marketindexCd=FX_USDKRW";

    // RestTemplate timeout 명시 — 외부 API hang 시 thread 무한 점유 방지.
    // koreaexim/네이버 응답이 5초 안에 안 오면 폴백 또는 캐시 활용.
    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f
                = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }

    // 캐시 (환율은 자주 안 바뀌므로 10분 캐시)
    private volatile ExchangeRateDto cachedRate;
    private volatile LocalDateTime cacheTime;
    private static final long CACHE_MINUTES = 10;

    /**
     * 현재 USD/KRW 환율 정보 조회
     */
    public ExchangeRateDto getCurrentExchangeRate() {
        // 캐시 체크
        if (cachedRate != null && cacheTime != null
                && cacheTime.isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return cachedRate;
        }

        ExchangeRateDto result = null;

        // 1순위: 한국수출입은행 API
        if (koreaeximApiKey != null && !koreaeximApiKey.isBlank()) {
            result = fetchFromKoreaExim();
        }

        // 2순위: 네이버 폴백
        if (result == null || result.getRate() == null) {
            log.info("한국수출입은행 API 실패 → 네이버 폴백");
            result = fetchFromNaver();
        }

        if (result != null && result.getRate() != null) {
            cachedRate = result;
            cacheTime = LocalDateTime.now();
        }

        return result != null ? result : ExchangeRateDto.builder()
                .interpretation("환율 정보를 불러올 수 없습니다")
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 한국수출입은행 Open API로 환율 조회
     */
    private ExchangeRateDto fetchFromKoreaExim() {
        try {
            String searchDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = String.format("%s?authkey=%s&searchdate=%s&data=AP01",
                    koreaeximApiUrl, koreaeximApiKey, searchDate);

            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) return null;

            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) return null;

            for (JsonNode node : root) {
                String curUnit = node.has("cur_unit") ? node.get("cur_unit").asText() : "";
                if (!"USD".equals(curUnit)) continue;

                // 매매기준율 (콤마 제거)
                String dealBasR = node.has("deal_bas_r") ? node.get("deal_bas_r").asText().replace(",", "") : null;
                if (dealBasR == null || dealBasR.isBlank()) return null;

                BigDecimal rate = new BigDecimal(dealBasR);

                // 전일 대비 변동
                BigDecimal change = null;
                if (node.has("bkpr") && node.has("deal_bas_r")) {
                    String bkpr = node.get("bkpr").asText().replace(",", "");
                    if (!bkpr.isBlank()) {
                        BigDecimal prevRate = new BigDecimal(bkpr);
                        change = rate.subtract(prevRate);
                    }
                }

                // 변동률 계산
                BigDecimal changeRate = null;
                if (change != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal prevRate = rate.subtract(change);
                    if (prevRate.compareTo(BigDecimal.ZERO) > 0) {
                        changeRate = change.divide(prevRate, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                }

                String trend = ExchangeRateDto.determineTrend(change);
                String signal = ExchangeRateDto.determineSignal(changeRate);
                String interpretation = ExchangeRateDto.generateInterpretation(changeRate, signal);

                log.info("환율 조회 완료 (수출입은행): {} ({}%)", rate, changeRate);
                return ExchangeRateDto.builder()
                        .rate(rate).change(change).changeRate(changeRate)
                        .trend(trend).signal(signal).interpretation(interpretation)
                        .fetchedAt(LocalDateTime.now())
                        .build();
            }

            log.warn("수출입은행 응답에 USD 데이터 없음");
            return null;
        } catch (Exception e) {
            log.warn("수출입은행 환율 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 네이버 금융 스크래핑 (폴백)
     */
    private ExchangeRateDto fetchFromNaver() {
        try {
            Document doc = Jsoup.connect(NAVER_EXCHANGE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .get();

            BigDecimal rate = null;
            Element rateElement = doc.selectFirst("p.no_today span.blind");
            if (rateElement != null) {
                rate = new BigDecimal(rateElement.text().replace(",", "").trim());
            }

            BigDecimal change = null;
            Element changeElement = doc.selectFirst("p.no_exday span.no_up span.blind");
            if (changeElement == null) {
                changeElement = doc.selectFirst("p.no_exday span.no_down span.blind");
            }
            if (changeElement != null) {
                change = new BigDecimal(changeElement.text().replace(",", "").trim());
                Element downCheck = doc.selectFirst("p.no_exday span.no_down");
                if (downCheck != null) change = change.negate();
            }

            BigDecimal changeRate = null;
            if (rate != null && change != null) {
                BigDecimal prevRate = rate.subtract(change);
                if (prevRate.compareTo(BigDecimal.ZERO) > 0) {
                    changeRate = change.divide(prevRate, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }

            String trend = ExchangeRateDto.determineTrend(change);
            String signal = ExchangeRateDto.determineSignal(changeRate);
            String interpretation = ExchangeRateDto.generateInterpretation(changeRate, signal);

            log.info("환율 조회 완료 (네이버 폴백): {} ({}%)", rate, changeRate);
            return ExchangeRateDto.builder()
                    .rate(rate).change(change).changeRate(changeRate)
                    .trend(trend).signal(signal).interpretation(interpretation)
                    .fetchedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("네이버 환율 조회도 실패: {}", e.getMessage());
            return null;
        }
    }
}
