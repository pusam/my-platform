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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
    // 토큰 발급/캐시/갱신/쿨다운/401 무효화 = 공유 단일 출처 (P3-8 선택B). 자체 토큰 캐시 없음.
    private final KisTokenManager tokenManager;

    public KisApiService(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        KisApiProperties kisApiProperties,
                        KisTokenManager tokenManager) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.kisApiProperties = kisApiProperties;
        this.tokenManager = tokenManager;
    }

    /** KIS 키 설정 여부 — 컨트롤러가 '미설정(503)'과 '정상 빈 결과(200)'를 구분하는 용도(§4c). */
    public boolean isConfigured() {
        return kisApiProperties.getAppKey() != null && !kisApiProperties.getAppKey().isBlank();
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

        String token = tokenManager.getAccessToken();
        if (token == null) {
            return new ArrayList<>();
        }
        try {

            // FHKST01010900: 투자자별 순매수 상위종목
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/investor-trend";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010900");
            headers.setContentType(MediaType.APPLICATION_JSON);

            String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

            URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")     // J=주식
                .queryParam("FID_COND_SCR_DIV_CODE", "20171")  // 화면구분
                .queryParam("FID_INPUT_ISCD", "0000")          // 전체
                .queryParam("FID_DIV_CLS_CODE", "0")           // 전체
                .queryParam("FID_INPUT_DATE_1", today)
                .queryParam("FID_RANK_SORT_CLS_CODE", "0")     // 순매수 상위
                .build()
                .toUri();

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, entity, String.class);

            return parseInvestorTrendResponse(response.getBody());
        } catch (Exception e) {
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 공유 토큰 CAS 무효화(P3-8), 재시도 없음
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

        String token = tokenManager.getAccessToken();
        if (token == null) {
            return new ArrayList<>();
        }
        try {

            // FHKST01010800: 연속 매수/매도 상위종목
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/continuous-buy";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010800");
            headers.setContentType(MediaType.APPLICATION_JSON);

            URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_COND_SCR_DIV_CODE", "20170")
                .queryParam("FID_INPUT_ISCD", "0000")
                .queryParam("FID_DIV_CLS_CODE", "0")
                .queryParam("FID_RANK_SORT_CLS_CODE", "0")
                .build()
                .toUri();

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, entity, String.class);

            return parseContinuousBuyResponse(response.getBody());
        } catch (Exception e) {
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 공유 토큰 CAS 무효화(P3-8), 재시도 없음
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

        String token = tokenManager.getAccessToken();
        if (token == null) {
            return new ArrayList<>();
        }
        try {

            // FHKST01010600: 거래량 순위
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/volume";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHKST01010600");
            headers.setContentType(MediaType.APPLICATION_JSON);

            URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "UN")  // KRX+NXT 통합 — 8-20시 거래량 반영
                .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                .queryParam("FID_INPUT_ISCD", "0000")
                .queryParam("FID_DIV_CLS_CODE", "0")
                .queryParam("FID_RANK_SORT_CLS_CODE", "0")
                .queryParam("FID_INPUT_PRICE_1", "")
                .queryParam("FID_INPUT_PRICE_2", "")
                .build()
                .toUri();

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, entity, String.class);

            return parseSupplySurgeResponse(response.getBody());
        } catch (Exception e) {
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 공유 토큰 CAS 무효화(P3-8), 재시도 없음
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
                    dto.setChangeRate(new BigDecimal(item.get("prdy_ctrt").asText()));
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

    /**
     * 국내 선물 현재가 조회 (KIS API)
     * @param contractCode 종목코드 (예: "10166" = KOSPI200 2026년 6월물)
     * @return 선물 시세 정보 Map (null if failed)
     */
    public Map<String, Object> getFuturesCurrentPrice(String contractCode) {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            log.warn("KIS API 키가 설정되지 않았습니다 (선물 조회).");
            return null;
        }

        String token = tokenManager.getAccessToken();
        if (token == null) {
            return null;
        }
        try {

            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-futureoption/v1/quotations/inquire-price";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHMIF10000000");
            headers.setContentType(MediaType.APPLICATION_JSON);

            URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "F")
                .queryParam("FID_INPUT_ISCD", contractCode)
                .build()
                .toUri();

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[KIS선물] 응답 오류: {}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String rtCd = root.path("rt_cd").asText("");
            if (!"0".equals(rtCd)) {
                log.warn("[KIS선물] API 에러: {} - {}", root.path("msg_cd").asText(), root.path("msg1").asText());
                return null;
            }

            // ⚠ 실측(2026-07-16 프로브): FHMIF10000000 응답 root 는 output1/output2/output3 — "output" 키는
            // 존재하지 않는다(기존 root.path("output") 은 항상 MissingNode → 이 메서드가 데이터를 반환한 적이
            // 없었고 KM 은 상시 Yahoo 폴백이었음). output1=선물 본체, output2=KOSPI 종합(0001),
            // output3=KOSPI200 현물(2001, 베이시스 다리 내장). 선물 본체만 맵으로 반환.
            // output1 이 빈 객체({})로 오는 케이스 확인됨 — 파생 시세 권한/계좌 요건 미충족 추정(§4c: 빈 응답은
            // null 반환으로 정직 강등, 소비자(GlobalFuturesService)가 Yahoo 폴백).
            JsonNode output = root.path("output1");
            if (output.isMissingNode() || output.isEmpty()) {
                log.debug("[KIS선물] output1 없음/빈 응답 (파생 시세 권한 또는 종목코드 확인 필요) — 소비자 폴백");
                return null;
            }

            // 응답 필드를 범용적으로 Map에 담아서 반환
            Map<String, Object> result = new HashMap<>();
            output.fields().forEachRemaining(f -> result.put(f.getKey(), f.getValue().asText("")));

            log.debug("[KIS선물] {} 조회 성공: {}", contractCode,
                result.getOrDefault("futs_prpr", result.getOrDefault("stck_prpr", "N/A")));
            return result;

        } catch (Exception e) {
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 공유 토큰 CAS 무효화(P3-8), 재시도 없음
            log.error("[KIS선물] {} 조회 실패: {}", contractCode, e.getMessage());
            return null;
        }
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

