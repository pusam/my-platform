package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국투자증권 API를 통한 투자자별 매매 데이터 수집 서비스
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class KisInvestorDataCollector {

    private final KisApiProperties kisApiProperties;
    private final InvestorDailyTradeRepository investorTradeRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 액세스 토큰 캐시
    private String accessToken;
    private long tokenExpiryTime;

    /**
     * 특정 일자의 투자자별 매매 데이터 수집
     * KOSPI 상위 50종목의 투자자별 매매 데이터를 수집합니다.
     */
    public Map<String, Integer> collectDailyInvestorTrades(LocalDate tradeDate) {
        Map<String, Integer> result = new HashMap<>();

        try {
            // 액세스 토큰 발급
            ensureAccessToken();

            String dateStr = tradeDate.format(DATE_FORMATTER);

            // 외국인 순매수 상위
            int foreignBuy = collectInvestorRanking("KOSPI", "FOREIGN", "BUY", dateStr, 50);
            result.put("KOSPI_FOREIGN_BUY", foreignBuy);

            // 외국인 순매도 상위
            int foreignSell = collectInvestorRanking("KOSPI", "FOREIGN", "SELL", dateStr, 50);
            result.put("KOSPI_FOREIGN_SELL", foreignSell);

            // 기관 순매수 상위
            int institutionBuy = collectInvestorRanking("KOSPI", "INSTITUTION", "BUY", dateStr, 50);
            result.put("KOSPI_INSTITUTION_BUY", institutionBuy);

            // 기관 순매도 상위
            int institutionSell = collectInvestorRanking("KOSPI", "INSTITUTION", "SELL", dateStr, 50);
            result.put("KOSPI_INSTITUTION_SELL", institutionSell);

            // 개인 순매수 상위
            int individualBuy = collectInvestorRanking("KOSPI", "INDIVIDUAL", "BUY", dateStr, 50);
            result.put("KOSPI_INDIVIDUAL_BUY", individualBuy);

            // 개인 순매도 상위
            int individualSell = collectInvestorRanking("KOSPI", "INDIVIDUAL", "SELL", dateStr, 50);
            result.put("KOSPI_INDIVIDUAL_SELL", individualSell);

            log.info("투자자별 매매 데이터 수집 완료: {}", result);

        } catch (Exception e) {
            log.error("투자자별 매매 데이터 수집 실패", e);
        }

        return result;
    }

    /**
     * 특정 투자자 유형의 순위 데이터 수집
     */
    private int collectInvestorRanking(String market, String investorType, String tradeType,
                                       String dateStr, int limit) {
        try {
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/investor-volume-rank";

            HttpHeaders headers = createHeaders();
            headers.set("tr_id", "FHPST01730000"); // 투자자별 순위 조회 TR_ID

            // 쿼리 파라미터 설정
            Map<String, String> params = new HashMap<>();
            params.put("fid_cond_mrkt_div_code", "J"); // KOSPI
            params.put("fid_cond_scr_div_code", getTradeTypeCode(investorType, tradeType));
            params.put("fid_input_iscd", "0000"); // 전체
            params.put("fid_div_cls_code", "0"); // 전체
            params.put("fid_input_date_1", dateStr);
            params.put("fid_rank_sort_cls_code", "0"); // 순매수 기준

            StringBuilder urlBuilder = new StringBuilder(url);
            urlBuilder.append("?");
            params.forEach((key, value) -> urlBuilder.append(key).append("=").append(value).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return parseAndSaveRankingData(response.getBody(), market, investorType, tradeType,
                        LocalDate.parse(dateStr, DATE_FORMATTER), limit);
            }

        } catch (Exception e) {
            log.error("투자자 순위 데이터 수집 실패: {} {} {}", market, investorType, tradeType, e);
        }

        return 0;
    }

    /**
     * API 응답을 파싱하고 DB에 저장
     */
    private int parseAndSaveRankingData(String responseBody, String market, String investorType,
                                        String tradeType, LocalDate tradeDate, int limit) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output == null || !output.isArray()) {
                log.warn("응답 데이터가 없습니다: {} {} {}", market, investorType, tradeType);
                return 0;
            }

            List<InvestorDailyTrade> trades = new ArrayList<>();
            int rank = 1;

            for (JsonNode item : output) {
                if (rank > limit) break;

                try {
                    InvestorDailyTrade trade = InvestorDailyTrade.builder()
                            .marketType(market)
                            .tradeDate(tradeDate)
                            .investorType(investorType)
                            .tradeType(tradeType)
                            .rankNum(rank)
                            .stockCode(item.get("mksc_shrn_iscd").asText()) // 종목코드
                            .stockName(item.get("hts_kor_isnm").asText())   // 종목명
                            .netBuyAmount(parseBigDecimal(item.get("ntby_qty")))  // 순매수량
                            .buyAmount(parseBigDecimal(item.get("shnu_vol")))     // 매수량
                            .sellAmount(parseBigDecimal(item.get("shnu_sll_vol"))) // 매도량
                            .currentPrice(parseBigDecimal(item.get("stck_prpr")))  // 현재가
                            .changeRate(parseBigDecimal(item.get("prdy_vrss_sign"))) // 등락률
                            .tradeVolume(item.get("acml_vol").asLong())  // 거래량
                            .build();

                    trades.add(trade);
                    rank++;

                } catch (Exception e) {
                    log.warn("데이터 파싱 실패", e);
                }
            }

            if (!trades.isEmpty()) {
                investorTradeRepository.saveAll(trades);
                log.info("저장 완료: {} {} {} - {}건", market, investorType, tradeType, trades.size());
                return trades.size();
            }

        } catch (Exception e) {
            log.error("응답 파싱 실패", e);
        }

        return 0;
    }

    /**
     * 액세스 토큰 발급 및 갱신
     */
    private void ensureAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            return;
        }

        try {
            String url = kisApiProperties.getBaseUrl() + "/oauth2/tokenP";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", kisApiProperties.getAppKey());
            body.put("appsecret", kisApiProperties.getAppSecret());

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                accessToken = root.get("access_token").asText();
                int expiresIn = root.get("expires_in").asInt();
                tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000L) - 60000; // 1분 여유

                log.info("KIS 액세스 토큰 발급 완료");
            }

        } catch (Exception e) {
            log.error("액세스 토큰 발급 실패", e);
            throw new RuntimeException("KIS API 인증 실패", e);
        }
    }

    /**
     * API 요청 헤더 생성
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("content-type", "application/json; charset=utf-8");
        headers.set("authorization", "Bearer " + accessToken);
        headers.set("appkey", kisApiProperties.getAppKey());
        headers.set("appsecret", kisApiProperties.getAppSecret());
        headers.set("custtype", "P"); // 개인
        return headers;
    }

    /**
     * 투자자 유형과 거래 유형에 따른 조회 코드 반환
     */
    private String getTradeTypeCode(String investorType, String tradeType) {
        // 한국투자증권 API의 투자자별 조회 코드
        String base = "";

        switch (investorType) {
            case "FOREIGN":
                base = "20"; // 외국인
                break;
            case "INSTITUTION":
                base = "30"; // 기관
                break;
            case "INDIVIDUAL":
                base = "10"; // 개인
                break;
        }

        // 매수/매도 구분
        if ("BUY".equals(tradeType)) {
            base += "1"; // 순매수
        } else {
            base += "2"; // 순매도
        }

        return base;
    }

    /**
     * 문자열을 BigDecimal로 변환
     */
    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }

        try {
            String value = node.asText().replaceAll("[^0-9.-]", "");
            return new BigDecimal(value);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
