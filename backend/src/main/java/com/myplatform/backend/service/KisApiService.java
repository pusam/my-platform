package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
import com.myplatform.backend.dto.ContinuousBuyStockDto;
import com.myplatform.backend.dto.InvestorTrendDto;
import com.myplatform.backend.dto.SupplySurgeStockDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국투자증권 API 서비스
 * - 투자자 매매동향
 * - 연속 매수 종목
 * - 수급 급등 종목
 */
@Service
public class KisApiService {

    private static final Logger log = LoggerFactory.getLogger(KisApiService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KisApiProperties kisApiProperties;
    private String accessToken;
    private long tokenExpireTime = 0;

    public KisApiService(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        KisApiProperties kisApiProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.kisApiProperties = kisApiProperties;
    }

    /**
     * 액세스 토큰 발급 (24시간 유효)
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

            // 토큰 만료 시간 설정 (여유있게 1시간 전에 갱신)
            this.tokenExpireTime = System.currentTimeMillis() + ((expiresIn - 3600) * 1000L);

            log.info("KIS API 액세스 토큰 발급 완료");
        } catch (Exception e) {
            log.error("KIS API 토큰 발급 실패", e);
        }
    }

    /**
     * 투자자 매매동향 조회 (외국인, 기관 순매수 상위)
     */
    @Cacheable(value = "investorTrend", unless = "#result == null || #result.isEmpty()")
    public List<InvestorTrendDto> getInvestorTrend() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            // FHKST01010900: 투자자별 순매수 상위종목
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/investor-trend";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + accessToken);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010900");
            headers.setContentType(MediaType.APPLICATION_JSON);

            String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

            // 쿼리 파라미터
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J"); // 시장구분: J=주식
            params.put("FID_COND_SCR_DIV_CODE", "20171"); // 화면구분코드
            params.put("FID_INPUT_ISCD", "0000"); // 입력 종목코드: 전체
            params.put("FID_DIV_CLS_CODE", "0"); // 분류구분: 0=전체
            params.put("FID_INPUT_DATE_1", today); // 조회일자
            params.put("FID_RANK_SORT_CLS_CODE", "0"); // 순위정렬: 0=순매수상위

            StringBuilder urlWithParams = new StringBuilder(url + "?");
            params.forEach((key, value) -> urlWithParams.append(key).append("=").append(value).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                urlWithParams.toString(), HttpMethod.GET, entity, String.class);

            return parseInvestorTrendResponse(response.getBody());
        } catch (Exception e) {
            log.error("투자자 매매동향 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 연속 매수 종목 조회
     */
    @Cacheable(value = "continuousBuy", unless = "#result == null || #result.isEmpty()")
    public List<ContinuousBuyStockDto> getContinuousBuyStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            // FHKST01010800: 연속 매수/매도 상위종목
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/continuous-buy";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + accessToken);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010800");
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 쿼리 파라미터
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J"); // 시장구분
            params.put("FID_COND_SCR_DIV_CODE", "20170"); // 화면구분
            params.put("FID_INPUT_ISCD", "0000"); // 종목코드
            params.put("FID_DIV_CLS_CODE", "0"); // 분류구분
            params.put("FID_RANK_SORT_CLS_CODE", "0"); // 순위정렬

            StringBuilder urlWithParams = new StringBuilder(url + "?");
            params.forEach((key, value) -> urlWithParams.append(key).append("=").append(value).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                urlWithParams.toString(), HttpMethod.GET, entity, String.class);

            return parseContinuousBuyResponse(response.getBody());
        } catch (Exception e) {
            log.error("연속 매수 종목 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 수급 급등 종목 조회 (거래량 급증)
     */
    @Cacheable(value = "supplySurge", unless = "#result == null || #result.isEmpty()")
    public List<SupplySurgeStockDto> getSupplySurgeStocks() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다.");
            return new ArrayList<>();
        }

        try {
            refreshAccessToken();
            if (accessToken == null) {
                return new ArrayList<>();
            }

            // FHKST01010600: 거래량 순위
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/volume";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + accessToken);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010600");
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 쿼리 파라미터
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J"); // 시장구분
            params.put("FID_COND_SCR_DIV_CODE", "20171"); // 화면구분
            params.put("FID_INPUT_ISCD", "0000"); // 종목코드
            params.put("FID_DIV_CLS_CODE", "0"); // 분류구분
            params.put("FID_RANK_SORT_CLS_CODE", "0"); // 순위정렬
            params.put("FID_INPUT_PRICE_1", ""); // 입력가격1
            params.put("FID_INPUT_PRICE_2", ""); // 입력가격2

            StringBuilder urlWithParams = new StringBuilder(url + "?");
            params.forEach((key, value) -> urlWithParams.append(key).append("=").append(value).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                urlWithParams.toString(), HttpMethod.GET, entity, String.class);

            return parseSupplySurgeResponse(response.getBody());
        } catch (Exception e) {
            log.error("수급 급등 종목 조회 실패", e);
            return new ArrayList<>();
        }
    }

    private List<InvestorTrendDto> parseInvestorTrendResponse(String responseBody) {
        List<InvestorTrendDto> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    InvestorTrendDto dto = new InvestorTrendDto();
                    dto.setStockCode(item.get("mksc_shrn_iscd").asText());
                    dto.setStockName(item.get("hts_kor_isnm").asText());
                    dto.setCurrentPrice(new BigDecimal(item.get("stck_prpr").asText()));
                    dto.setChangeAmount(new BigDecimal(item.get("prdy_vrss").asText()));
                    dto.setChangeRate(new BigDecimal(item.get("prdy_vrss_sign").asText()));
                    dto.setForeignerVolume(Long.parseLong(item.get("frgn_ntby_qty").asText()));
                    dto.setInstitutionVolume(Long.parseLong(item.get("orgn_ntby_qty").asText()));

                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.error("투자자 매매동향 응답 파싱 실패", e);
        }
        return result;
    }

    private List<ContinuousBuyStockDto> parseContinuousBuyResponse(String responseBody) {
        List<ContinuousBuyStockDto> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    ContinuousBuyStockDto dto = new ContinuousBuyStockDto();
                    dto.setStockCode(item.get("mksc_shrn_iscd").asText());
                    dto.setStockName(item.get("hts_kor_isnm").asText());
                    dto.setCurrentPrice(new BigDecimal(item.get("stck_prpr").asText()));
                    dto.setChangeAmount(new BigDecimal(item.get("prdy_vrss").asText()));
                    dto.setChangeRate(new BigDecimal(item.get("prdy_ctrt").asText()));
                    dto.setVolume(Long.parseLong(item.get("acml_vol").asText()));
                    dto.setContinuousDays(item.get("cont_day").asInt());

                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.error("연속 매수 종목 응답 파싱 실패", e);
        }
        return result;
    }

    private List<SupplySurgeStockDto> parseSupplySurgeResponse(String responseBody) {
        List<SupplySurgeStockDto> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    SupplySurgeStockDto dto = new SupplySurgeStockDto();
                    dto.setStockCode(item.get("mksc_shrn_iscd").asText());
                    dto.setStockName(item.get("hts_kor_isnm").asText());
                    dto.setCurrentPrice(new BigDecimal(item.get("stck_prpr").asText()));
                    dto.setChangeAmount(new BigDecimal(item.get("prdy_vrss").asText()));
                    dto.setChangeRate(new BigDecimal(item.get("prdy_ctrt").asText()));
                    dto.setVolume(Long.parseLong(item.get("acml_vol").asText()));

                    // 거래량 증가율 계산
                    if (item.has("acml_vol") && item.has("prdy_vol")) {
                        long todayVol = Long.parseLong(item.get("acml_vol").asText());
                        long yesterdayVol = Long.parseLong(item.get("prdy_vol").asText());
                        if (yesterdayVol > 0) {
                            BigDecimal rate = BigDecimal.valueOf((todayVol - yesterdayVol) * 100.0 / yesterdayVol)
                                .setScale(2, RoundingMode.HALF_UP);
                            dto.setVolumeChangeRate(rate);
                        }
                    }

                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.error("수급 급등 종목 응답 파싱 실패", e);
        }
        return result;
    }
}

