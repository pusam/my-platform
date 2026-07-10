package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
import com.myplatform.backend.dto.MarketIndicatorStockDto;
import com.myplatform.backend.entity.MarketIndicatorSnapshot;
import com.myplatform.backend.repository.MarketIndicatorSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * 주식 시장 지표 서비스 (DB 기반)
 * - 52주 신고가/신저가
 * - 시가총액 순위
 * - 거래대금 순위
 * - 등락률 상위/하위
 *
 * 매일 장 마감 후 18:00에 데이터 수집하여 DB에 저장
 */
@Service
public class MarketIndicatorService {

    private static final Logger log = LoggerFactory.getLogger(MarketIndicatorService.class);

    private static final String TYPE_PRICE_RISE = "PRICE_RISE";
    private static final String TYPE_PRICE_FALL = "PRICE_FALL";
    // 52주 신고가는 CompositeAlertService 가 알림 조건으로 사용 중. tr_id 가 깨진 상태라 현재
    // 빈 데이터를 반환하지만, 외부 호출자 호환 위해 메서드는 유지 (미래에 정확한 KIS 호출로 부활 가능).
    private static final String TYPE_52W_HIGH = "52W_HIGH";

    // 장중 실시간 등락률 워머용 Redis L2 캐시
    private static final String CACHE_PRICE_MOVERS = "priceMovers";
    private static final String KEY_RISE = "RISE";
    private static final String KEY_FALL = "FALL";
    private static final Duration TTL_PRICE_MOVERS = Duration.ofMinutes(2);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KisApiProperties kisApiProperties;
    private final MarketIndicatorSnapshotRepository snapshotRepository;
    private final RedisCacheService redisCacheService;
    // 토큰 발급/캐시/갱신/쿨다운/401 무효화 = 공유 단일 출처 (P3-8 선택B). 자체 토큰 캐시 없음.
    private final KisTokenManager tokenManager;

    public MarketIndicatorService(RestTemplate restTemplate,
                                 ObjectMapper objectMapper,
                                 KisApiProperties kisApiProperties,
                                 MarketIndicatorSnapshotRepository snapshotRepository,
                                 RedisCacheService redisCacheService,
                                 KisTokenManager tokenManager) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.kisApiProperties = kisApiProperties;
        this.snapshotRepository = snapshotRepository;
        this.redisCacheService = redisCacheService;
        this.tokenManager = tokenManager;
    }

    /**
     * 52주 신고가 종목 — CompositeAlertService 알림 조건용.
     * 현재는 일배치 KIS 호출이 정리됨에 따라 항상 빈 리스트를 반환(알림 조건 미발화).
     * 미래에 살릴 시 정확한 KIS tr_id 로 채우는 별도 워머/배치 추가 필요.
     */
    public List<MarketIndicatorStockDto> get52WeekHighStocks() {
        return getIndicatorData(TYPE_52W_HIGH, "52주 신고가");
    }

    /**
     * 급등주 (등락률 상위)
     * - 장중 워머가 1분 단위로 KIS 호출해 채워두는 Redis L2 먼저 조회.
     * - 미스 시 일배치 DB 스냅샷으로 폴백(전일자).
     */
    public List<MarketIndicatorStockDto> getPriceRiseTopStocks() {
        List<MarketIndicatorStockDto> cached = redisCacheService.get(
                CACHE_PRICE_MOVERS, KEY_RISE,
                new TypeReference<List<MarketIndicatorStockDto>>() {});
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return getIndicatorData(TYPE_PRICE_RISE, "등락률 상위");
    }

    /**
     * 급락주 (등락률 하위)
     */
    public List<MarketIndicatorStockDto> getPriceFallTopStocks() {
        List<MarketIndicatorStockDto> cached = redisCacheService.get(
                CACHE_PRICE_MOVERS, KEY_FALL,
                new TypeReference<List<MarketIndicatorStockDto>>() {});
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return getIndicatorData(TYPE_PRICE_FALL, "등락률 하위");
    }

    /**
     * 장중 실시간 등락률 상위/하위 KIS 갱신 (워머 전용).
     * - {@code MarketCacheWarmerService} 가 장중에 1분 주기로 호출.
     * - KIS FHKST01010500/600 → Redis L2 만 갱신, DB 스냅샷은 일 1회 18시 배치 그대로 둠.
     */
    public void refreshPriceMoversFromKis() {
        if (kisApiProperties.getAppKey() == null || kisApiProperties.getAppKey().isBlank()) {
            return;
        }
        String token = tokenManager.getAccessToken();
        if (token == null) {
            log.warn("[PriceMovers] 액세스 토큰 발급 실패 - KIS 갱신 스킵");
            return;
        }
        try {
            List<MarketIndicatorStockDto> rise = fetchPriceMoversFromApi(token, true, TYPE_PRICE_RISE, "등락률 상위");
            if (rise != null && !rise.isEmpty()) {
                redisCacheService.put(CACHE_PRICE_MOVERS, KEY_RISE, rise, TTL_PRICE_MOVERS);
            }
            Thread.sleep(300);
            List<MarketIndicatorStockDto> fall = fetchPriceMoversFromApi(token, false, TYPE_PRICE_FALL, "등락률 하위");
            if (fall != null && !fall.isEmpty()) {
                redisCacheService.put(CACHE_PRICE_MOVERS, KEY_FALL, fall, TTL_PRICE_MOVERS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[PriceMovers] KIS 갱신 실패: {}", e.getMessage());
        }
    }

    /**
     * KIS 등락률 순위 API 호출.
     * tr_id: <b>FHPST01700000</b> (상위/하위 공통, {@code FID_RANK_SORT_CLS_CODE} 로 정렬방향 구분).
     */
    private List<MarketIndicatorStockDto> fetchPriceMoversFromApi(String token, boolean isRise, String indicatorType, String description) {
        try {
            String url = kisApiProperties.getBaseUrl() + "/uapi/domestic-stock/v1/ranking/fluctuation";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", kisApiProperties.getAppKey());
            headers.set("appsecret", kisApiProperties.getAppSecret());
            headers.set("tr_id", "FHPST01700000");
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "UN");                 // KRX+NXT 통합 — 8-20시 등락률 반영
            params.put("FID_COND_SCR_DIV_CODE", "20170");               // 화면구분
            params.put("FID_INPUT_ISCD", "0000");                       // 전체
            params.put("FID_RANK_SORT_CLS_CODE", isRise ? "0" : "1");   // 0=상승률, 1=하락률
            params.put("FID_INPUT_CNT_1", "0");                         // 0=전체
            params.put("FID_PRC_CLS_CODE", "0");                        // 가격구분: 전체
            params.put("FID_INPUT_PRICE_1", "");
            params.put("FID_INPUT_PRICE_2", "");
            params.put("FID_VOL_CNT", "");
            params.put("FID_TRGT_CLS_CODE", "0");
            params.put("FID_TRGT_EXLS_CLS_CODE", "0");
            params.put("FID_DIV_CLS_CODE", "0");
            params.put("FID_RSFL_RATE1", "");
            params.put("FID_RSFL_RATE2", "");

            StringBuilder urlWithParams = new StringBuilder(url + "?");
            params.forEach((k, v) -> urlWithParams.append(k).append("=").append(v).append("&"));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                urlWithParams.toString(), HttpMethod.GET, entity, String.class);

            List<MarketIndicatorStockDto> parsed = parseRankingResponse(response.getBody(), indicatorType);
            log.debug("{} API 조회 완료 - {}건", description, parsed.size());
            if (parsed.isEmpty()) {
                log.warn("[{}] KIS 응답 파싱 결과 0건 — tr_id/파라미터 변경 의심", indicatorType);
            }
            return parsed;
        } catch (Exception e) {
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 공유 토큰 CAS 무효화(P3-8), 재시도 없음
            log.error("{} API 조회 실패", description, e);
            return new ArrayList<>();
        }
    }

    /**
     * DB에서 지표 데이터 조회 (없으면 API 호출)
     */
    private List<MarketIndicatorStockDto> getIndicatorData(String indicatorType, String description) {
        try {
            // DB에서 최신 데이터 조회
            Optional<MarketIndicatorSnapshot> snapshot = snapshotRepository.findLatestByIndicatorType(indicatorType);

            if (snapshot.isPresent() && snapshot.get().getDataJson() != null) {
                List<MarketIndicatorStockDto> data = objectMapper.readValue(
                    snapshot.get().getDataJson(),
                    new TypeReference<List<MarketIndicatorStockDto>>() {}
                );
                log.debug("{} DB 조회 완료: {}개 종목 (날짜: {})", description, data.size(), snapshot.get().getSnapshotDate());
                return data;
            }

            // DB에 데이터가 없으면 빈 리스트 반환 (배치에서 수집됨)
            log.warn("{} 데이터가 없습니다. 배치 작업을 기다려주세요.", description);
            return new ArrayList<>();

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

                    // 필드명이 API 종류에 따라 다를 수 있으므로 null 체크
                    JsonNode codeNode = item.get("mksc_shrn_iscd");
                    if (codeNode == null) codeNode = item.get("stck_shrn_iscd");
                    if (codeNode == null) continue;

                    JsonNode nameNode = item.get("hts_kor_isnm");
                    if (nameNode == null) nameNode = item.get("stck_kor_isnm");
                    if (nameNode == null) continue;

                    JsonNode priceNode = item.get("stck_prpr");
                    if (priceNode == null) continue;

                    dto.setStockCode(codeNode.asText());
                    dto.setStockName(nameNode.asText());
                    dto.setCurrentPrice(new BigDecimal(priceNode.asText()));
                    dto.setChangeAmount(item.has("prdy_vrss") ? new BigDecimal(item.get("prdy_vrss").asText()) : BigDecimal.ZERO);
                    dto.setChangeRate(item.has("prdy_ctrt") ? new BigDecimal(item.get("prdy_ctrt").asText()) : BigDecimal.ZERO);

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
                        dto.setTradingValue(new BigDecimal(item.get("acml_tr_pbmn").asText()));
                    }
                    if (item.has("hts_avls")) {
                        BigDecimal marketCapWon = new BigDecimal(item.get("hts_avls").asText());
                        dto.setMarketCap(marketCapWon.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP));
                    }

                    if (item.has("w52_hgpr")) {
                        dto.setWeek52High(new BigDecimal(item.get("w52_hgpr").asText()));
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
                        if (dto.getCurrentPrice() != null && dto.getWeek52Low().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal rate = dto.getCurrentPrice()
                                .subtract(dto.getWeek52Low())
                                .divide(dto.getWeek52Low(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                            dto.setWeek52LowRate(rate.setScale(2, RoundingMode.HALF_UP));
                        }
                    }

                    if (item.has("per")) {
                        dto.setPer(new BigDecimal(item.get("per").asText()));
                    }
                    if (item.has("pbr")) {
                        dto.setPbr(new BigDecimal(item.get("pbr").asText()));
                    }

                    dto.setRank(rank++);
                    dto.setIndicatorType(indicatorType);

                    result.add(dto);

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
