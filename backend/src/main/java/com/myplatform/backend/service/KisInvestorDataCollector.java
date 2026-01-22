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
     * 한국투자증권 API: 국내기관_외국인 매매종목가집계 (FHPTJ04400000)
     *
     * 주의: 이 API는 외국인(1)과 기관(2)만 지원합니다.
     *       개인(INDIVIDUAL)은 별도 처리가 필요합니다.
     */
    private int collectInvestorRanking(String market, String investorType, String tradeType,
                                       String dateStr, int limit) {
        // 개인(INDIVIDUAL)은 이 API에서 지원하지 않음
        if ("INDIVIDUAL".equals(investorType)) {
            log.info("개인 투자자 데이터는 foreign-institution-total API에서 지원하지 않습니다. 건너뜁니다.");
            return 0;
        }

        try {
            // 국내기관_외국인 매매종목가집계 API
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/quotations/foreign-institution-total";

            HttpHeaders headers = createHeaders();
            headers.set("tr_id", "FHPTJ04400000");

            // 투자자 구분: 1=외국인, 2=기관계
            String investorCode = "FOREIGN".equals(investorType) ? "1" : "2";
            // 순매수/순매도: 0=순매수상위, 1=순매도상위
            String rankSortCode = "BUY".equals(tradeType) ? "0" : "1";

            String urlWithParams = url +
                "?FID_COND_MRKT_DIV_CODE=V" +       // V: 전체
                "&FID_COND_SCR_DIV_CODE=16449" +   // 화면 구분 코드
                "&FID_INPUT_ISCD=0000" +            // 전체 종목
                "&FID_DIV_CLS_CODE=1" +             // 1=금액정렬, 0=수량정렬
                "&FID_RANK_SORT_CLS_CODE=" + rankSortCode +  // 0=순매수상위, 1=순매도상위
                "&FID_ETC_CLS_CODE=" + investorCode;         // 1=외국인, 2=기관계

            log.info("API 호출: {} {} - {}", investorType, tradeType, urlWithParams);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    urlWithParams, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                String responseBody = response.getBody();
                log.info("API 응답: {}", responseBody != null && responseBody.length() > 300 ?
                    responseBody.substring(0, 300) + "..." : responseBody);

                return parseAndSaveRankingData(responseBody, market, investorType, tradeType,
                        LocalDate.parse(dateStr, DATE_FORMATTER), limit);
            }

        } catch (Exception e) {
            log.error("순위 데이터 수집 실패: {} {} {} - {}", market, investorType, tradeType, e.getMessage());
        }

        return 0;
    }

    /**
     * API 응답을 파싱하고 DB에 저장
     */
    private int parseAndSaveRankingData(String responseBody, String market, String investorType,
                                        String tradeType, LocalDate tradeDate, int limit) {
        try {
            log.debug("파싱 시작 - 응답 길이: {}", responseBody != null ? responseBody.length() : 0);

            JsonNode root = objectMapper.readTree(responseBody);

            // 응답 코드 확인
            String rtCd = root.has("rt_cd") ? root.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.error("API 오류: {} - {}", rtCd, root.has("msg1") ? root.get("msg1").asText() : "");
                return 0;
            }

            JsonNode output = root.get("output");

            if (output == null || !output.isArray() || output.size() == 0) {
                log.warn("응답 데이터가 비어있습니다: {} {} {} (output size: {})",
                    market, investorType, tradeType, output != null ? output.size() : 0);
                return 0;
            }

            log.info("파싱할 데이터 개수: {}", output.size());

            List<InvestorDailyTrade> trades = new ArrayList<>();
            int rank = 1;

            for (JsonNode item : output) {
                if (rank > limit) break;

                try {
                    // foreign-institution-total API 필드명
                    String stockCode = getJsonValue(item, "mksc_shrn_iscd", "stck_shrn_iscd");
                    String stockName = getJsonValue(item, "hts_kor_isnm");

                    if (stockCode == null || stockName == null) {
                        log.debug("필수 필드 누락: rank {} - 사용 가능 필드: {}", rank,
                            item.fieldNames().hasNext() ? String.join(", ",
                                java.util.stream.StreamSupport.stream(
                                    java.util.Spliterators.spliteratorUnknownSize(item.fieldNames(), 0), false)
                                .limit(10).toArray(String[]::new)) : "없음");
                        continue;
                    }

                    // 순매수대금 (백만원 단위)
                    BigDecimal netBuyAmount = getJsonBigDecimal(item, "ntby_tr_pbmn", "total_tr_pbmn");
                    // 순매수수량
                    Long netBuyQty = getJsonLong(item, "ntby_qty", "total_qty");
                    // 현재가
                    BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
                    // 등락률
                    BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");
                    // 누적거래량
                    Long tradeVolume = getJsonLong(item, "acml_vol");

                    InvestorDailyTrade trade = InvestorDailyTrade.builder()
                            .marketType(market)
                            .tradeDate(tradeDate)
                            .investorType(investorType)
                            .tradeType(tradeType)
                            .rankNum(rank)
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .netBuyAmount(netBuyAmount)
                            .buyAmount(BigDecimal.ZERO)
                            .sellAmount(BigDecimal.ZERO)
                            .currentPrice(currentPrice)
                            .changeRate(changeRate)
                            .tradeVolume(tradeVolume)
                            .build();

                    trades.add(trade);
                    rank++;

                } catch (Exception e) {
                    log.warn("데이터 파싱 실패: rank {} - {}", rank, e.getMessage());
                }
            }

            if (!trades.isEmpty()) {
                investorTradeRepository.saveAll(trades);
                log.info("✅ 저장 완료: {} {} {} - {}건", market, investorType, tradeType, trades.size());
                return trades.size();
            } else {
                log.warn("⚠️ 파싱된 데이터가 없습니다: {} {} {}", market, investorType, tradeType);
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
     * JSON 필드값 가져오기 (여러 필드명 시도)
     */
    private String getJsonValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return node.get(fieldName).asText();
            }
        }
        return null;
    }

    /**
     * JSON BigDecimal 값 가져오기
     */
    private BigDecimal getJsonBigDecimal(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return parseBigDecimal(node.get(fieldName));
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * JSON Long 값 가져오기
     */
    private Long getJsonLong(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                try {
                    return node.get(fieldName).asLong();
                } catch (Exception e) {
                    // 문자열인 경우 파싱 시도
                    try {
                        return Long.parseLong(node.get(fieldName).asText().replaceAll("[^0-9]", ""));
                    } catch (Exception ex) {
                        return 0L;
                    }
                }
            }
        }
        return 0L;
    }

    /**
     * 투자자 유형 코드 반환
     */
    private String getInvestorCode(String investorType) {
        switch (investorType) {
            case "FOREIGN":
                return "1"; // 외국인
            case "INSTITUTION":
                return "2"; // 기관
            case "INDIVIDUAL":
                return "3"; // 개인
            default:
                return "1";
        }
    }

    /**
     * 거래 유형 코드 반환
     */
    private String getTradeCode(String tradeType) {
        if ("BUY".equals(tradeType)) {
            return "1"; // 매수
        } else {
            return "2"; // 매도
        }
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
