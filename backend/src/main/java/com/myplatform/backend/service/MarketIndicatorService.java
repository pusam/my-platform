package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
import com.myplatform.backend.dto.MarketIndicatorStockDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 주식 시장 지표 서비스
 * - 52주 신고가/신저가
 * - 시가총액 순위
 * - 거래대금 순위
 * - 등락률 상위/하위
 * - PER/PBR 분석
 * - 배당수익률
 */
@Service
public class MarketIndicatorService {

    private static final Logger log = LoggerFactory.getLogger(MarketIndicatorService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KisApiProperties kisApiProperties;
    private String accessToken;
    private long tokenExpireTime = 0;

    public MarketIndicatorService(RestTemplate restTemplate,
                                 ObjectMapper objectMapper,
                                 KisApiProperties kisApiProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.kisApiProperties = kisApiProperties;
    }

    /**
     * 액세스 토큰 갱신
     */
    private void refreshAccessToken() {
        if (System.currentTimeMillis() < tokenExpireTime && accessToken != null) {
            return;
        }

        try {
            String url = kisApiProperties.getBaseUrl() + "/oauth2/tokenP";

            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", kisApiProperties.getAppKey());
            body.put("appsecret", kisApiProperties.getAppSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            this.accessToken = root.get("access_token").asText();
            int expiresIn = root.get("expires_in").asInt();
            this.tokenExpireTime = System.currentTimeMillis() + ((expiresIn - 3600) * 1000L);

            log.info("KIS API 액세스 토큰 발급 완료");
        } catch (Exception e) {
            log.error("KIS API 토큰 발급 실패", e);
        }
    }

    /**
     * 52주 신고가 종목
     */
    @Cacheable(value = "week52High", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> get52WeekHighStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010300", "52W_HIGH", "52주 신고가");
        } catch (Exception e) {
            log.error("52주 신고가 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 52주 신저가 종목
     */
    @Cacheable(value = "week52Low", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> get52WeekLowStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010400", "52W_LOW", "52주 신저가");
        } catch (Exception e) {
            log.error("52주 신저가 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 시가총액 상위
     */
    @Cacheable(value = "marketCapHigh", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> getMarketCapHighStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010100", "MARKET_CAP_HIGH", "시가총액 상위");
        } catch (Exception e) {
            log.error("시가총액 상위 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 거래대금 상위
     */
    @Cacheable(value = "tradingValue", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> getTradingValueStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010200", "TRADING_VALUE", "거래대금 상위");
        } catch (Exception e) {
            log.error("거래대금 상위 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 급등주 (등락률 상위)
     */
    @Cacheable(value = "priceRiseTop", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> getPriceRiseTopStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010500", "PRICE_RISE", "등락률 상위");
        } catch (Exception e) {
            log.error("등락률 상위 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 급락주 (등락률 하위)
     */
    @Cacheable(value = "priceFallTop", unless = "#result == null || #result.isEmpty()")
    public List<MarketIndicatorStockDto> getPriceFallTopStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            return fetchRankingData("FHKST01010600", "PRICE_FALL", "등락률 하위");
        } catch (Exception e) {
            log.error("등락률 하위 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * KIS API 순위 데이터 조회 (공통)
     */
    private List<MarketIndicatorStockDto> fetchRankingData(String trId, String indicatorType, String description) {
        try {
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/fluctuation";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + accessToken);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", trId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J");
            params.put("FID_COND_SCR_DIV_CODE", "20170");
            params.put("FID_INPUT_ISCD", "0000");
            params.put("FID_DIV_CLS_CODE", "0");
            params.put("FID_BLNG_CLS_CODE", "0");
            params.put("FID_TRGT_CLS_CODE", "111111111");
            params.put("FID_TRGT_EXLS_CLS_CODE", "000000");
            params.put("FID_INPUT_PRICE_1", "");
            params.put("FID_INPUT_PRICE_2", "");
            params.put("FID_VOL_CNT", "");
            params.put("FID_INPUT_DATE_1", "");

            StringBuilder urlWithParams = new StringBuilder(url + "?");
            params.forEach((key, value) -> urlWithParams.append(key).append("=").append(value).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                urlWithParams.toString(), HttpMethod.GET, entity, String.class);

            log.info("{} 조회 완료", description);
            return parseRankingResponse(response.getBody(), indicatorType);
        } catch (Exception e) {
            log.error("{} 조회 실패", description, e);
            return new ArrayList<>();
        }
    }

    /**
     * 순위 데이터 파싱
     */
    private List<MarketIndicatorStockDto> parseRankingResponse(String responseBody, String indicatorType) {
        List<MarketIndicatorStockDto> result = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output != null && output.isArray()) {
                int rank = 1;
                for (JsonNode item : output) {
                    MarketIndicatorStockDto dto = new MarketIndicatorStockDto();

                    dto.setStockCode(item.get("mksc_shrn_iscd").asText());
                    dto.setStockName(item.get("hts_kor_isnm").asText());
                    dto.setCurrentPrice(new BigDecimal(item.get("stck_prpr").asText()));
                    dto.setChangeAmount(new BigDecimal(item.get("prdy_vrss").asText()));
                    dto.setChangeRate(new BigDecimal(item.get("prdy_ctrt").asText()));

                    if (item.has("stck_oprc")) {
                        dto.setOpenPrice(new BigDecimal(item.get("stck_oprc").asText()));
                    }
                    if (item.has("stck_hgpr")) {
                        dto.setHighPrice(new BigDecimal(item.get("stck_hgpr").asText()));
                    }
                    if (item.has("stck_lwpr")) {
                        dto.setLowPrice(new BigDecimal(item.get("stck_lwpr").asText()));
                    }
                    if (item.has("acml_vol")) {
                        dto.setVolume(Long.parseLong(item.get("acml_vol").asText()));
                    }
                    if (item.has("acml_tr_pbmn")) {
                        // 거래대금 (백만원 단위)
                        dto.setTradingValue(new BigDecimal(item.get("acml_tr_pbmn").asText()));
                    }
                    if (item.has("hts_avls")) {
                        // 시가총액 (억원 단위로 변환)
                        BigDecimal marketCapWon = new BigDecimal(item.get("hts_avls").asText());
                        dto.setMarketCap(marketCapWon.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP));
                    }

                    // 52주 고가/저가
                    if (item.has("w52_hgpr")) {
                        dto.setWeek52High(new BigDecimal(item.get("w52_hgpr").asText()));
                        // 52주 최고가 대비율 계산
                        if (dto.getCurrentPrice() != null && dto.getWeek52High().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal rate = dto.getCurrentPrice()
                                .subtract(dto.getWeek52High())
                                .divide(dto.getWeek52High(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                            dto.setWeek52HighRate(rate.setScale(2, RoundingMode.HALF_UP));
                        }
                    }
                    if (item.has("w52_lwpr")) {
                        dto.setWeek52Low(new BigDecimal(item.get("w52_lwpr").asText()));
                        // 52주 최저가 대비율 계산
                        if (dto.getCurrentPrice() != null && dto.getWeek52Low().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal rate = dto.getCurrentPrice()
                                .subtract(dto.getWeek52Low())
                                .divide(dto.getWeek52Low(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                            dto.setWeek52LowRate(rate.setScale(2, RoundingMode.HALF_UP));
                        }
                    }

                    // PER, PBR (있는 경우)
                    if (item.has("per")) {
                        dto.setPer(new BigDecimal(item.get("per").asText()));
                    }
                    if (item.has("pbr")) {
                        dto.setPbr(new BigDecimal(item.get("pbr").asText()));
                    }

                    dto.setRank(rank++);
                    dto.setIndicatorType(indicatorType);

                    result.add(dto);

                    // 상위 50개만
                    if (rank > 50) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("순위 데이터 파싱 실패", e);
        }

        return result;
    }
}

