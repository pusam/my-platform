package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import com.myplatform.core.util.DateTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 주식 시세 조회 서비스
 * - 한국투자증권 API 우선 사용 (실시간)
 * - API 키 미설정 시 네이버 증권 API 폴백 (15분 지연)
 */
@Service
@Transactional
public class StockPriceService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceService.class);

    // 네이버 증권 API (폴백용)
    private static final String NAVER_STOCK_API = "https://m.stock.naver.com/api/stock/%s/basic";
    private static final String NAVER_SEARCH_API_BASE = "https://m.stock.naver.com/front-api/search/autoComplete";

    // 네이버 API 요청 헤더 (409 차단 방지)
    private static final String NAVER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String NAVER_REFERER = "https://m.stock.naver.com/";

    // 네이버 서킷브레이커 (연속 에러 시 요청 중단)
    private final AtomicInteger naverConsecutiveErrors = new AtomicInteger(0);
    private volatile LocalDateTime naverCooldownUntil = null;
    private final AtomicInteger naverCircuitOpenCount = new AtomicInteger(0); // 반복 오픈 횟수
    private static final int NAVER_MAX_CONSECUTIVE_ERRORS = 3;  // 3회 연속 에러 시 서킷 오픈
    private static final int NAVER_BASE_COOLDOWN_SECONDS = 60;  // 기본 60초 쿨다운

    // 409 반복 실패 종목 자동 블랙리스트 (종목코드 → 블랙리스트 해제 시각)
    private final ConcurrentHashMap<String, LocalDateTime> naverBlacklist = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> naverFailCount = new ConcurrentHashMap<>();
    private static final int NAVER_BLACKLIST_THRESHOLD = 3;     // 3회 409 → 블랙리스트
    private static final int NAVER_BLACKLIST_MINUTES = 30;      // 30분간 스킵

    private final RestTemplate restTemplate;
    private final StockPriceRepository stockPriceRepository;
    private final ObjectMapper objectMapper;
    private final KoreaInvestmentService kisService;
    private final StockMasterService stockMasterService;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private io.micrometer.core.instrument.Timer searchTimer;
    private io.micrometer.core.instrument.DistributionSummary searchResultCount;

    // 캐시 (종목코드 -> 시세)
    private final ConcurrentHashMap<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();

    // 분봉 거래대금 캐시 (종목코드_분 -> {거래대금, 캐시시간})
    private final ConcurrentHashMap<String, MinuteTradingCache> minuteTradingCache = new ConcurrentHashMap<>();
    private static final int MINUTE_CACHE_SECONDS = 300;  // 5분간 캐시 (섹터 흐름은 급변하지 않음)

    // 분봉 캐시용 내부 클래스
    private static class MinuteTradingCache {
        final BigDecimal value;
        final long timestamp;

        MinuteTradingCache(BigDecimal value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < MINUTE_CACHE_SECONDS * 1000L;
        }
    }

    public StockPriceService(RestTemplate restTemplate,
                             StockPriceRepository stockPriceRepository,
                             ObjectMapper objectMapper,
                             KoreaInvestmentService kisService,
                             StockMasterService stockMasterService,
                             io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.stockPriceRepository = stockPriceRepository;
        this.objectMapper = objectMapper;
        this.kisService = kisService;
        this.stockMasterService = stockMasterService;
        this.meterRegistry = meterRegistry;
        // 검색 latency / 결과수 / 출처 메트릭
        this.searchTimer = io.micrometer.core.instrument.Timer.builder("stock_search.duration")
                .description("종목 검색 응답 시간 (캐시 hit 포함)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.searchResultCount = io.micrometer.core.instrument.DistributionSummary
                .builder("stock_search.result_count")
                .description("검색 1회당 반환 결과 수")
                .register(meterRegistry);
    }

    /**
     * 만료 캐시 정리 (메모리 누수 방지)
     * - priceCache: 5분 이상 된 엔트리 제거
     * - minuteTradingCache: 만료된 엔트리 제거
     *
     * 2026-04-24 주기 확대: 평일 16시 1회 → 매시간 정각.
     * 주말/야간에도 수시 cleanup (priceCache 가 며칠간 누적되던 문제 해결).
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void cleanupExpiredCache() {
        int priceRemoved = 0;
        int minuteRemoved = 0;

        // priceCache: fetchedAt이 5분 이상 지난 엔트리 제거 (removeIf로 안전하게)
        var priceIt = priceCache.entrySet().iterator();
        while (priceIt.hasNext()) {
            if (!isValidCache(priceIt.next().getValue(), 5)) {
                priceIt.remove();
                priceRemoved++;
            }
        }

        // minuteTradingCache: isValid() false인 엔트리 제거
        var minuteIt = minuteTradingCache.entrySet().iterator();
        while (minuteIt.hasNext()) {
            if (!minuteIt.next().getValue().isValid()) {
                minuteIt.remove();
                minuteRemoved++;
            }
        }

        // 네이버 블랙리스트 — 만료된 엔트리만 제거(아직 유효한 차단은 보존, isBlacklisted 자가만료와 일관).
        // 기존엔 매시간 전체 clear 라 아직 안 끝난 차단까지 풀려 문제 종목 반복 재시도 → 차단 무력화였음.
        int blacklistRemoved = 0;
        LocalDateTime nowTs = DateTimeUtil.kstNow();
        var blIt = naverBlacklist.entrySet().iterator();
        while (blIt.hasNext()) {
            if (blIt.next().getValue().isBefore(nowTs)) {
                blIt.remove();
                blacklistRemoved++;
            }
        }
        naverFailCount.clear();              // 실패 카운터는 시간당 리셋(차단 전 누적값 — 신선한 재시도 기회)
        naverCircuitOpenCount.set(0);        // 서킷 반복오픈 카운터(전역 건전성) 리셋

        log.info("[캐시정리] priceCache {}건 제거 (잔여 {}건), minuteTradingCache {}건 제거 (잔여 {}건), 네이버 블랙리스트 만료 {}건 제거 (잔여 {}건)",
                priceRemoved, priceCache.size(), minuteRemoved, minuteTradingCache.size(), blacklistRemoved, naverBlacklist.size());
    }

    /**
     * [개선 1] 여러 종목 시세 일괄 조회 (N+1 문제 해결)
     *
     * <p>외부 HTTP 호출(KIS/네이버)을 종목 수만큼 순차 처리하므로 클래스 레벨 {@code @Transactional}
     * 영향권에 들면 외부 호출 대기시간 동안 DB 커넥션을 점유 → Hikari leakDetectionThreshold(60s)
     * 초과로 leak 경고 발생. {@code NOT_SUPPORTED} 로 트랜잭션을 끊고, 내부의 {@code repository.save()}
     * 는 Spring Data 가 자체 짧은 트랜잭션을 만들어 처리.
     *
     * @param stockCodes 종목코드 리스트
     * @return 종목코드 -> 시세 DTO 맵
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, StockPriceDto> getStockPrices(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, StockPriceDto> result = new HashMap<>();

        // 1. 메모리 캐시에서 먼저 조회
        List<String> missingCodes = new ArrayList<>();
        int cacheMinutes = kisService.isConfigured() ? 1 : 10;

        for (String code : stockCodes) {
            StockPriceDto cached = priceCache.get(code);
            if (cached != null && isValidCache(cached, cacheMinutes)) {
                result.put(code, cached);
            } else {
                missingCodes.add(code);
            }
        }

        // 2. 메모리 캐시 미스 → DB에서 추가 조회 (KIS 호출량 감소).
        //    [N+1 → batch IN 쿼리로 변경]
        //    이전: missingCodes.size() 만큼 findTopBy... 호출 — 종목당 자동 read-tx
        //          → 100종목이면 100번 connection acquire/release. KIS 호출 끼면 누적 60s+
        //          → Hikari leakDetectionThreshold 발동 (false positive 일 수 있으나 부하 ↑)
        //    변경: findLatestByStockCodes(List) 1회 호출 — 단일 짧은 tx → connection 즉시 release.
        if (!missingCodes.isEmpty()) {
            Map<String, StockPrice> dbMap;
            try {
                dbMap = stockPriceRepository.findLatestByStockCodes(missingCodes).stream()
                        .collect(Collectors.toMap(
                                StockPrice::getStockCode,
                                p -> p,
                                (a, b) -> a.getFetchedAt() != null
                                        && (b.getFetchedAt() == null
                                            || a.getFetchedAt().isAfter(b.getFetchedAt())) ? a : b));
            } catch (Exception e) {
                log.warn("DB batch lookup 실패 — 종목 {}건 KIS 폴백: {}", missingCodes.size(), e.getMessage());
                dbMap = new HashMap<>();
            }

            List<String> stillMissing = new ArrayList<>(missingCodes.size());
            for (String code : missingCodes) {
                StockPrice dbPrice = dbMap.get(code);
                if (dbPrice != null) {
                    StockPriceDto dto = entityToDto(dbPrice);
                    if (isValidCache(dto, cacheMinutes)) {
                        priceCache.put(code, dto);
                        result.put(code, dto);
                        continue;
                    }
                }
                stillMissing.add(code);
            }
            missingCodes = stillMissing;
        }

        // 3. 여전히 미스인 종목만 API 호출 (순차 처리 - 안정성 우선)
        if (!missingCodes.isEmpty()) {
            // KIS 사용 불가 시 네이버 폴백 → 더 긴 딜레이 적용
            boolean usingKis = kisService.isConfigured() && kisService.isTokenAvailable();
            // KIS 는 KisApiRateLimiter 가 400ms 간격을 이미 보장하므로 여기서는 추가 sleep 불필요.
            // 네이버 폴백 시에는 자체 간격 유지 (limiter 밖 경로).
            int delayMs = usingKis ? 0 : NAVER_REQUEST_DELAY_MS;
            String apiSource = usingKis ? "KIS" : "Naver(폴백)";

            log.info("Batch 시세 조회 시작 - 소스: {}, 캐시 히트: {}, 미스: {} (순차 처리, {}ms 추가 간격)",
                    apiSource, result.size(), missingCodes.size(), delayMs);
            long startTime = System.currentTimeMillis();
            int successCount = 0;

            for (int i = 0; i < missingCodes.size(); i++) {
                String code = missingCodes.get(i);

                // 네이버 서킷브레이커 체크 (연속 에러 시 중단)
                if (!usingKis && isNaverCircuitOpen()) {
                    log.warn("네이버 서킷브레이커 오픈 - 나머지 {}건 스킵", missingCodes.size() - i);
                    break;
                }

                try {
                    // 네이버 경로일 때만 추가 sleep (KIS 는 limiter 가 간격 제어)
                    if (i > 0 && delayMs > 0) {
                        Thread.sleep(delayMs);
                    }

                    StockPriceDto fetched = fetchStockPrice(code);
                    if (fetched != null) {
                        result.put(code, fetched);
                        priceCache.put(code, fetched);
                        successCount++;

                        // DB 저장은 비동기로 (속도 향상)
                        final StockPriceDto toSave = fetched;
                        CompletableFuture.runAsync(() -> {
                            try {
                                stockPriceRepository.save(dtoToEntity(toSave));
                            } catch (Exception e) {
                                log.debug("DB 저장 실패 [{}]: {}", code, e.getMessage());
                            }
                        });
                    }

                    // 10개마다 진행상황 로그
                    if ((i + 1) % 10 == 0) {
                        log.debug("시세 조회 진행 중: {}/{}, 성공: {}", i + 1, missingCodes.size(), successCount);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("시세 조회 중단됨 (interrupt)");
                    break;
                } catch (Exception e) {
                    log.warn("종목 시세 조회 실패 [{}]: {}", code, e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Batch 시세 조회 완료 - 소스: {}, 성공: {}/{}, 소요: {}ms", apiSource, successCount, missingCodes.size(), elapsed);
        }

        return result;
    }

    /**
     * [빠른 조회] 캐시/DB에서만 시세 조회 (API 호출 안 함)
     * - 턴어라운드 등 빠른 응답이 필요한 API에서 사용
     * - 캐시 미스 시 null 반환
     */
    public Map<String, StockPriceDto> getStockPricesFromCacheOnly(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, StockPriceDto> result = new HashMap<>();
        int cacheMinutes = kisService.isConfigured() ? 5 : 30; // 캐시 유효시간 늘림

        // 1. 메모리 캐시에서 먼저 조회
        List<String> dbLookupCodes = new ArrayList<>();
        for (String code : stockCodes) {
            StockPriceDto cached = priceCache.get(code);
            if (cached != null && isValidCache(cached, cacheMinutes)) {
                result.put(code, cached);
            } else {
                dbLookupCodes.add(code);
            }
        }

        // 2. DB batch lookup (N+1 → 1 query, connection 점유 시간 ↓)
        if (!dbLookupCodes.isEmpty()) {
            Map<String, StockPrice> dbMap;
            try {
                dbMap = stockPriceRepository.findLatestByStockCodes(dbLookupCodes).stream()
                        .collect(Collectors.toMap(
                                StockPrice::getStockCode, p -> p,
                                (a, b) -> a.getFetchedAt() != null
                                        && (b.getFetchedAt() == null
                                            || a.getFetchedAt().isAfter(b.getFetchedAt())) ? a : b));
            } catch (Exception e) {
                log.warn("DB batch lookup 실패 (cacheOnly): {}", e.getMessage());
                dbMap = new HashMap<>();
            }
            for (String code : dbLookupCodes) {
                StockPrice dbPrice = dbMap.get(code);
                if (dbPrice != null) {
                    StockPriceDto dto = entityToDto(dbPrice);
                    if (isValidCache(dto, cacheMinutes)) {
                        priceCache.put(code, dto);
                        result.put(code, dto);
                    }
                }
                // API 호출은 하지 않음 - 없으면 그냥 null
            }
        }

        log.debug("캐시 전용 시세 조회 - 요청: {}, 캐시 히트: {}", stockCodes.size(), result.size());
        return result;
    }

    /**
     * 종목코드로 주식 시세 조회.
     *
     * 단건 조회는 사용자가 종목 상세 진입 시 반드시 필요한 값이라 완전히 KIS 를
     * 끊을 수는 없음. 대신 다음 두 단계로 KIS 부하를 완화:
     *  1) 메모리 캐시 TTL = 1분 (KIS) / 10분 (네이버 모드) 으로 기존 유지
     *  2) DB 재활용 허용 시간을 15분으로 완화 → 대부분의 요청이 DB 히트로 흡수
     *     (장중 분 단위 정확도는 트레이딩 봇만 필요, UI 상세는 15분 허용 가능)
     *  3) 그래도 미스인 경우에만 KIS 호출 — 사용자가 직접 누른 "진짜 필요한 순간"
     *
     * <p>{@link #getStockPrices} 와 동일한 이유로 {@code NOT_SUPPORTED} — KIS HTTP 호출 대기로
     * 인한 커넥션 점유 방지.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StockPriceDto getStockPrice(String stockCode) {
        // 시세 캐시 경로 = L1 로컬(priceCache, ConcurrentHashMap) → DB(MariaDB) → KIS/Naver.
        //   ※ 시세는 Redis(L2) 비경유다. 전역 "L1 Caffeine → L2 Redis → L3 MariaDB"는 섹터·수급·
        //     AI전략 등 '다른 도메인' 캐시 얘기이고, 시세는 단일 경로 불변식 유지를 위해 메모리+DB만 쓴다.
        // 캐시 확인 (한투 API는 1분, 네이버는 10분)
        StockPriceDto cached = priceCache.get(stockCode);
        int cacheMinutes = kisService.isConfigured() ? 1 : 10;
        if (cached != null && isValidCache(cached, cacheMinutes)) {
            return cached;
        }

        // DB 에서 최근 데이터 조회 — 단건 UI 조회는 DB 15분 이내면 충분.
        // (기존: cacheMinutes 와 동일 = 1분 → 캐시 미스 시 거의 무조건 KIS 호출되던 문제)
        int dbRecentMinutes = 15;
        Optional<StockPrice> dbPrice = stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(stockCode);
        if (dbPrice.isPresent()) {
            StockPriceDto dto = entityToDto(dbPrice.get());
            if (isValidCache(dto, dbRecentMinutes)) {
                priceCache.put(stockCode, dto);
                return dto;
            }
        }

        // API에서 조회 (정말 오래된 경우만)
        StockPriceDto fetched = fetchStockPrice(stockCode);
        if (fetched != null) {
            // DB 저장
            stockPriceRepository.save(dtoToEntity(fetched));
            priceCache.put(stockCode, fetched);
        }

        return fetched;
    }

    /**
     * API에서 주식 시세 조회 (한투 우선, 네이버 폴백)
     */
    private StockPriceDto fetchStockPrice(String stockCode) {
        // 한국투자증권 API 우선 사용
        if (kisService.isConfigured()) {
            if (kisService.isTokenAvailable()) {
                StockPriceDto kisPrice = fetchFromKoreaInvestment(stockCode);
                if (kisPrice != null) {
                    // ★ KIS가 등락률 0%를 반환하면 네이버에서 등락률만 보충
                    boolean changeRateMissing = kisPrice.getChangeRate() == null
                            || kisPrice.getChangeRate().compareTo(BigDecimal.ZERO) == 0;
                    boolean changePriceMissing = kisPrice.getChangePrice() == null
                            || kisPrice.getChangePrice().compareTo(BigDecimal.ZERO) == 0;

                    if (changeRateMissing && changePriceMissing && kisPrice.getCurrentPrice() != null) {
                        supplementFromNaver(kisPrice, stockCode);
                    }
                    return kisPrice;
                }
                log.warn("한투 API 조회 실패 [{}], 네이버 폴백", stockCode);
            } else {
                log.debug("한투 토큰 미발급 상태, 네이버 폴백: {}", stockCode);
            }
        }

        // 네이버 증권 API 폴백
        return fetchFromNaver(stockCode);
    }

    /**
     * KIS 응답에 등락률이 없을 때 네이버에서 보충
     */
    private void supplementFromNaver(StockPriceDto kisDto, String stockCode) {
        try {
            StockPriceDto naverDto = fetchFromNaver(stockCode);
            if (naverDto == null) return;

            if (naverDto.getChangeRate() != null && naverDto.getChangeRate().compareTo(BigDecimal.ZERO) != 0) {
                kisDto.setChangeRate(naverDto.getChangeRate());
                log.debug("[시세보충] {} 등락률 네이버 보충: {}%", stockCode, naverDto.getChangeRate());
            }
            if (kisDto.getChangePrice() == null || kisDto.getChangePrice().compareTo(BigDecimal.ZERO) == 0) {
                if (naverDto.getChangePrice() != null) {
                    kisDto.setChangePrice(naverDto.getChangePrice());
                }
            }
        } catch (Exception e) {
            log.debug("[시세보충] {} 네이버 보충 실패: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 한국투자증권 API에서 시세 조회
     * [장전 처리] 08:00~09:00 사이에는 일봉 API에서 어제 등락률을 가져옴
     */
    private StockPriceDto fetchFromKoreaInvestment(String stockCode) {
        try {
            JsonNode response = kisService.getStockPrice(stockCode);
            if (response == null) {
                return null;
            }

            // 응답 코드 확인
            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                String msg = response.has("msg1") ? response.get("msg1").asText() : "Unknown error";
                log.warn("한투 API 응답 오류: {} - {}", rtCd, msg);
                return null;
            }

            JsonNode output = response.get("output");
            if (output == null) {
                return null;
            }

            StockPriceDto dto = new StockPriceDto();
            dto.setStockCode(stockCode);
            dto.setStockName(getTextValue(output, "hts_kor_isnm")); // 종목명
            stockMasterService.cacheName(stockCode, dto.getStockName(), "KIS");
            dto.setCurrentPrice(getBigDecimalValue(output, "stck_prpr")); // 현재가
            dto.setOpenPrice(getBigDecimalValue(output, "stck_oprc")); // 시가
            dto.setHighPrice(getBigDecimalValue(output, "stck_hgpr")); // 고가
            dto.setLowPrice(getBigDecimalValue(output, "stck_lwpr")); // 저가
            dto.setChangePrice(getBigDecimalValue(output, "prdy_vrss")); // 전일대비
            dto.setChangeRate(getBigDecimalValue(output, "prdy_ctrt")); // 전일대비율
            dto.setVolume(getBigDecimalValue(output, "acml_vol")); // 누적거래량
            dto.setPreviousDayVolume(getBigDecimalValue(output, "prdy_vol")); // 전일 거래량
            // 누적 거래대금 계산 (KIS API는 직접 제공 안 함 → 현재가 × 거래량)
            if (dto.getCurrentPrice() != null && dto.getVolume() != null
                    && dto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0
                    && dto.getVolume().compareTo(BigDecimal.ZERO) > 0) {
                dto.setAccumulatedTradingValue(dto.getCurrentPrice().multiply(dto.getVolume()));
            }
            dto.setFetchedAt(DateTimeUtil.kstNow());
            dto.setDataSource("KIS"); // 데이터 출처

            // ★ 가격 이상치 진단 가드 — 현재가가 화면마다 실거래가의 정수배(예: ×10)로
            //    표시되던 버그 추적용. 파싱/저장 코드엔 ×10 로직이 없음을 확인했으므로,
            //    KIS 응답 자체 또는 통합시세(UN) 필드 매핑 문제인지 운영 로그로 확정한다.
            //    가격을 보정하지는 않는다(정상 종목 훼손 방지) — 탐지·로깅만.
            //    정상: 저가 ≤ 현재가 ≤ 고가. 현재가만 ×10이면 이 범위를 크게 벗어남.
            warnIfPriceOutlier(stockCode, output, dto);

            // 등락률이 없거나 0인지 확인
            boolean changeRateIsZeroOrNull = dto.getChangeRate() == null
                    || dto.getChangeRate().compareTo(BigDecimal.ZERO) == 0;

            // 1. 먼저 전일대비(prdy_vrss)로 계산 시도
            if (changeRateIsZeroOrNull) {
                boolean hasValidChangePrice = dto.getChangePrice() != null
                        && dto.getChangePrice().compareTo(BigDecimal.ZERO) != 0;

                if (hasValidChangePrice && dto.getCurrentPrice() != null) {
                    BigDecimal previousClose = dto.getCurrentPrice().subtract(dto.getChangePrice());
                    if (previousClose.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal calculatedRate = dto.getChangePrice()
                                .divide(previousClose, 4, java.math.RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, java.math.RoundingMode.HALF_UP);
                        dto.setChangeRate(calculatedRate);
                        changeRateIsZeroOrNull = false;
                        log.debug("등락률 계산 (prdy_vrss): {} = {}%", stockCode, calculatedRate);
                    }
                }
            }

            // 2. 여전히 등락률이 0이면 → 일봉 API에서 직전 등락률 조회 (장전뿐 아니라 장중에도)
            if (changeRateIsZeroOrNull) {
                BigDecimal dailyChangeRate = fetchYesterdayChangeRateFromDaily(stockCode);
                if (dailyChangeRate != null) {
                    dto.setChangeRate(dailyChangeRate);
                    dto.setDataSource("KIS_DAILY");
                    log.debug("[등락률 폴백] 일봉 API에서 등락률 적용: {} = {}%", stockCode, dailyChangeRate);
                }
            }

            log.debug("한투 API 시세 조회 성공: {} - {}원, 등락률 {}%",
                    dto.getStockName(), dto.getCurrentPrice(), dto.getChangeRate());
            return dto;

        } catch (Exception e) {
            log.error("한투 API 시세 파싱 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 장전 시간대인지 확인 (08:00 ~ 09:00)
     * 주말은 제외 (토,일은 장 안 열림)
     */
    private boolean isPreMarketTime() {
        LocalDateTime now = DateTimeUtil.kstNow();
        int hour = now.getHour();
        java.time.DayOfWeek dayOfWeek = now.getDayOfWeek();

        // 주말 제외
        if (dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            return false;
        }

        // 08:00 ~ 09:00 사이
        return hour >= 8 && hour < 9;
    }

    /**
     * 일봉 API에서 어제(가장 최근 거래일) 등락률 조회
     * @param stockCode 종목코드
     * @return 어제 등락률 (%) 또는 null
     */
    private BigDecimal fetchYesterdayChangeRateFromDaily(String stockCode) {
        try {
            // 일봉 데이터 조회 (최근 5일)
            JsonNode response = kisService.getDailyPrices(stockCode, 5);
            if (response == null) {
                return null;
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray() || output2.size() == 0) {
                log.warn("[일봉] {} - output2 데이터 없음", stockCode);
                return null;
            }

            // output2[0] = 가장 최근 거래일 (어제)
            JsonNode yesterday = output2.get(0);

            // prdy_ctrt (전일대비율) 추출
            if (yesterday.has("prdy_ctrt")) {
                String rateStr = yesterday.get("prdy_ctrt").asText();
                if (rateStr != null && !rateStr.isEmpty()) {
                    BigDecimal rate = new BigDecimal(rateStr);
                    log.debug("[일봉] {} - 어제 등락률: {}%", stockCode, rate);
                    return rate;
                }
            }

            // prdy_ctrt가 없으면 종가로 직접 계산
            if (output2.size() >= 2) {
                JsonNode day1 = output2.get(0); // 어제
                JsonNode day2 = output2.get(1); // 그저께

                BigDecimal close1 = getBigDecimalFromNode(day1, "stck_clpr");
                BigDecimal close2 = getBigDecimalFromNode(day2, "stck_clpr");

                if (close1 != null && close2 != null && close2.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal rate = close1.subtract(close2)
                            .divide(close2, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    log.debug("[일봉] {} - 종가 기반 등락률 계산: {}%", stockCode, rate);
                    return rate;
                }
            }

        } catch (Exception e) {
            log.warn("[일봉] {} - 어제 등락률 조회 실패: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * JsonNode에서 BigDecimal 추출 (일봉용)
     */
    private BigDecimal getBigDecimalFromNode(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        String value = node.get(field).asText();
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 종목 검색 (종목명 또는 종목코드로 검색).
     *
     * 응답 시간 최적화:
     *  - @Cacheable("stockSearch", 1분 TTL): 동일 키워드 재요청은 캐시 히트
     *  - DB 결과가 있으면 Naver 호출 skip (Naver 는 100ms~수초 + 차단 위험)
     *  - 결과 가격 채우기는 batch 쿼리 1회 (이전엔 N+1)
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "stockSearch",
            key = "T(java.util.Objects).toString(#keyword,'').toLowerCase().trim()",
            sync = true)
    public List<StockPriceDto> searchStocks(String keyword) {
        if (keyword == null || keyword.isBlank()) return java.util.Collections.emptyList();

        // 메트릭: 캐시 hit 는 Caffeine recordStats() 로 자동 노출되므로,
        // 이 timer 는 cache miss 처리 시간(검색 본체)만 측정.
        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start(meterRegistry);

        List<StockPriceDto> results = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String source = "empty";

        // 1순위: stock_master DB
        try {
            for (com.myplatform.backend.entity.StockMaster m :
                    stockMasterService.search(keyword, 20)) {
                if (!seen.add(m.getStockCode())) continue;
                results.add(buildBareDto(m.getStockCode(), m.getStockName()));
                if (results.size() >= 20) break;
            }
            if (!results.isEmpty()) source = "db";
        } catch (Exception e) {
            log.warn("stock_master 검색 실패: {}", e.getMessage());
        }

        // DB 에서 1건이라도 찾으면 Naver 안 감 (응답 속도 + 차단 위험 감소).
        // Naver 는 마스터에 아예 없는 케이스 (신규 상장 등) 에만 호출.
        if (results.isEmpty()) {
            searchFromNaver(keyword, results, seen);
            if (!results.isEmpty()) source = "naver";
        }

        // 가격은 마지막에 batch 로 한 번만 채움 (첫 10건만).
        fillCachedPricesBatch(results);

        // 메트릭 기록 — 출처별 카운터, 결과수, 응답시간
        io.micrometer.core.instrument.Counter.builder("stock_search.total")
                .tag("source", source).register(meterRegistry).increment();
        searchResultCount.record(results.size());
        sample.stop(searchTimer);

        return results;
    }

    /** Naver 검색 — DB 에서 0건일 때만 호출되는 fallback. */
    private void searchFromNaver(String keyword, List<StockPriceDto> results, java.util.Set<String> seen) {
        try {
            URI url = buildNaverSearchUrl(keyword);
            log.debug("종목 검색 폴백(Naver): {}", keyword);

            HttpHeaders headers = createNaverHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String responseBody = response.getBody();
            if (responseBody == null) return;

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultNode = root.get("result");
            if (resultNode == null) resultNode = root.get("stocks");
            JsonNode items = resultNode != null ? resultNode.get("items") : null;
            if (items == null && resultNode != null && resultNode.isArray()) items = resultNode;
            if (items == null || !items.isArray()) return;

            for (JsonNode item : items) {
                String stockCode = getFirstAvailableText(item, "code", "stockCode", "itemCode", "cd", "srtnCd");
                String stockName = getFirstAvailableText(item, "name", "stockName", "itemName", "nm", "itmsNm");
                if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) continue;
                if (!seen.add(stockCode)) continue;

                results.add(buildBareDto(stockCode, stockName));
                // 신규 상장 → 마스터에 자동 적재
                stockMasterService.cacheName(stockCode, stockName, "NAVER");
                if (results.size() >= 20) break;
            }
        } catch (Exception e) {
            log.warn("Naver 종목 검색 실패: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * §16-10: 한글 키워드는 UTF-8 로 1회 인코딩한 {@code URI} 를 RestTemplate 에 넘긴다.
     * String 을 넘기면 RestTemplate 이 재인코딩(% → %25)해 네이버가 키워드를 못 알아듣는다
     * (2026-07-01 재료 100% NONE 사건과 동형 — NaverSearchService.buildSearchUrl 참조).
     */
    static URI buildNaverSearchUrl(String keyword) {
        return UriComponentsBuilder.fromUriString(NAVER_SEARCH_API_BASE)
                .queryParam("query", keyword)
                .queryParam("target", "stock")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    private StockPriceDto buildBareDto(String stockCode, String stockName) {
        StockPriceDto dto = new StockPriceDto();
        dto.setStockCode(stockCode);
        dto.setStockName(stockName);
        dto.setCurrentPrice(BigDecimal.ZERO);
        dto.setChangeRate(BigDecimal.ZERO);
        dto.setFetchedAt(DateTimeUtil.kstNow());
        return dto;
    }

    /**
     * 검색 결과의 첫 10건에 대해 캐시된 가격을 batch 로 채움.
     *  - priceCache (메모리) 먼저 → miss 만 DB 한 번에 IN 쿼리 → KIS 호출 X
     *  - 이전: 종목당 별도 DB 쿼리 (N+1)
     */
    private void fillCachedPricesBatch(List<StockPriceDto> dtos) {
        if (dtos.isEmpty()) return;
        int limit = Math.min(dtos.size(), 10);
        int cacheMinutes = kisService.isConfigured() ? 15 : 60;

        java.util.Map<String, StockPriceDto> priceMap = new java.util.HashMap<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String code = dtos.get(i).getStockCode();
            StockPriceDto cached = priceCache.get(code);
            if (cached != null && isValidCache(cached, cacheMinutes)) {
                priceMap.put(code, cached);
            } else {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            try {
                for (StockPrice sp : stockPriceRepository.findLatestByStockCodes(missing)) {
                    StockPriceDto dbDto = entityToDto(sp);
                    if (isValidCache(dbDto, cacheMinutes)) {
                        priceMap.put(sp.getStockCode(), dbDto);
                    }
                }
            } catch (Exception e) {
                log.warn("배치 가격 조회 실패: {}", e.getMessage());
            }
        }
        for (int i = 0; i < limit; i++) {
            StockPriceDto dto = dtos.get(i);
            StockPriceDto price = priceMap.get(dto.getStockCode());
            if (price != null) {
                dto.setCurrentPrice(price.getCurrentPrice());
                dto.setChangeRate(price.getChangeRate());
                dto.setDataSource(price.getDataSource());
            }
        }
    }

    /**
     * 단건 가격 채우기 (사용처 거의 없으나 호환 유지).
     */
    private void fillCachedPrice(StockPriceDto dto) {
        String stockCode = dto.getStockCode();
        StockPriceDto cached = priceCache.get(stockCode);
        int cacheMinutes = kisService.isConfigured() ? 15 : 60;
        if (cached == null || !isValidCache(cached, cacheMinutes)) {
            Optional<StockPrice> dbPrice = stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(stockCode);
            if (dbPrice.isPresent()) {
                StockPriceDto dbDto = entityToDto(dbPrice.get());
                if (isValidCache(dbDto, cacheMinutes)) {
                    cached = dbDto;
                }
            }
        }
        if (cached != null) {
            dto.setCurrentPrice(cached.getCurrentPrice());
            dto.setChangeRate(cached.getChangeRate());
            dto.setDataSource(cached.getDataSource());
        }
    }

    /**
     * 네이버 증권 API에서 개별 종목 시세 조회 (폴백)
     * - 서킷브레이커: 연속 에러 시 요청 중단
     * - 409 Conflict 처리: 쿨다운 적용
     */
    private StockPriceDto fetchFromNaver(String stockCode) {
        // 블랙리스트 체크 (409 반복 실패 종목)
        if (isNaverBlacklisted(stockCode)) {
            log.debug("네이버 블랙리스트 종목 - {} 스킵", stockCode);
            return null;
        }

        // 서킷브레이커 체크
        if (isNaverCircuitOpen()) {
            log.debug("네이버 서킷브레이커 오픈 상태 - {} 스킵", stockCode);
            return null;
        }

        try {
            String url = String.format(NAVER_STOCK_API, stockCode);

            HttpHeaders headers = createNaverHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String responseBody = response.getBody();
            if (responseBody != null) {
                // 성공 → 에러 카운터 리셋
                naverConsecutiveErrors.set(0);
                JsonNode root = objectMapper.readTree(responseBody);
                return parseNaverStockDetail(root, stockCode);
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            if (statusCode == 409) {
                // 종목별 409 카운트 → 반복 실패 종목 블랙리스트
                int stockFails = naverFailCount
                        .computeIfAbsent(stockCode, k -> new AtomicInteger(0))
                        .incrementAndGet();
                if (stockFails >= NAVER_BLACKLIST_THRESHOLD) {
                    naverBlacklist.put(stockCode, DateTimeUtil.kstNow().plusMinutes(NAVER_BLACKLIST_MINUTES));
                    log.warn("네이버 블랙리스트 추가 [{}] - {}분간 스킵 ({}회 연속 409)", stockCode, NAVER_BLACKLIST_MINUTES, stockFails);
                }

                // 서킷브레이커 (점진적 쿨다운)
                int errors = naverConsecutiveErrors.incrementAndGet();
                log.warn("네이버 API 409 Conflict [{}] (연속 에러 {}/{})", stockCode, errors, NAVER_MAX_CONSECUTIVE_ERRORS);
                if (errors >= NAVER_MAX_CONSECUTIVE_ERRORS) {
                    int openCount = naverCircuitOpenCount.incrementAndGet();
                    int cooldownSeconds = Math.min(NAVER_BASE_COOLDOWN_SECONDS * openCount, 600); // 60s → 120s → 180s, 최대 10분
                    naverCooldownUntil = DateTimeUtil.kstNow().plusSeconds(cooldownSeconds);
                    log.error("네이버 서킷브레이커 오픈 ({}회차) - {}초간 요청 차단 (쿨다운: {})", openCount, cooldownSeconds, naverCooldownUntil);
                }
            } else if (statusCode == 429) {
                int errors = naverConsecutiveErrors.incrementAndGet();
                log.warn("네이버 API 429 Too Many Requests [{}] (연속 에러 {})", stockCode, errors);
                if (errors >= NAVER_MAX_CONSECUTIVE_ERRORS) {
                    int openCount = naverCircuitOpenCount.incrementAndGet();
                    int cooldownSeconds = Math.min(NAVER_BASE_COOLDOWN_SECONDS * openCount, 600);
                    naverCooldownUntil = DateTimeUtil.kstNow().plusSeconds(cooldownSeconds);
                }
            } else {
                log.error("네이버 API HTTP {} [{}]: {}", statusCode, stockCode, e.getMessage());
            }
        } catch (Exception e) {
            naverConsecutiveErrors.incrementAndGet();
            log.error("네이버 증권 API 주식 시세 조회 실패: {} - {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 네이버 API 요청 헤더 생성 (409 차단 방지)
     */
    private HttpHeaders createNaverHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", NAVER_USER_AGENT);
        headers.set("Referer", NAVER_REFERER);
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.set("Connection", "keep-alive");
        return headers;
    }

    /**
     * 네이버 서킷브레이커 오픈 여부 확인
     */
    private boolean isNaverCircuitOpen() {
        if (naverCooldownUntil != null) {
            if (DateTimeUtil.kstNow().isBefore(naverCooldownUntil)) {
                return true;  // 아직 쿨다운 중
            }
            // 쿨다운 만료 → 서킷 닫기
            naverCooldownUntil = null;
            naverConsecutiveErrors.set(0);
            log.info("네이버 서킷브레이커 닫힘 - 요청 재개");
        }
        return false;
    }

    /**
     * 종목별 네이버 블랙리스트 확인 (409 반복 실패 종목 스킵)
     */
    private boolean isNaverBlacklisted(String stockCode) {
        LocalDateTime until = naverBlacklist.get(stockCode);
        if (until == null) return false;
        if (DateTimeUtil.kstNow().isAfter(until)) {
            naverBlacklist.remove(stockCode);
            naverFailCount.remove(stockCode);
            return false;
        }
        return true;
    }

    /**
     * 네이버 금융 API 응답 파싱
     */
    private StockPriceDto parseNaverStockDetail(JsonNode root, String stockCode) {
        try {
            StockPriceDto dto = new StockPriceDto();

            dto.setStockCode(stockCode);
            dto.setStockName(root.get("stockName").asText());
            dto.setCurrentPrice(parsePrice(root.get("closePrice")));
            dto.setOpenPrice(parsePrice(root.has("openPrice") ? root.get("openPrice") : null));
            dto.setHighPrice(parsePrice(root.has("highPrice") ? root.get("highPrice") : null));
            dto.setLowPrice(parsePrice(root.has("lowPrice") ? root.get("lowPrice") : null));
            dto.setChangeRate(parsePrice(root.get("fluctuationsRatio")));
            // 전일대비 금액
            if (root.has("compareToPreviousClosePrice")) {
                dto.setChangePrice(parsePrice(root.get("compareToPreviousClosePrice")));
            }
            dto.setVolume(root.has("accumulatedTradingVolume")
                    ? BigDecimal.valueOf(root.get("accumulatedTradingVolume").asLong())
                    : BigDecimal.ZERO);

            // 누적 거래대금 파싱
            if (root.has("accumulatedTradingValue")) {
                dto.setAccumulatedTradingValue(BigDecimal.valueOf(root.get("accumulatedTradingValue").asLong()));
            }

            // 시가총액 파싱 (네이버 API: marketCap 또는 marketValue)
            if (root.has("marketCap")) {
                dto.setMarketCap(BigDecimal.valueOf(root.get("marketCap").asLong()));
            } else if (root.has("marketValue")) {
                dto.setMarketCap(BigDecimal.valueOf(root.get("marketValue").asLong()));
            }

            // PER 파싱
            if (root.has("per")) {
                dto.setPer(parsePrice(root.get("per")));
            } else if (root.has("stockEndStockStatisticItemInfos")) {
                // 네이버 상세 API 구조에서 PER 찾기
                JsonNode infos = root.get("stockEndStockStatisticItemInfos");
                if (infos != null && infos.isArray()) {
                    for (JsonNode info : infos) {
                        if ("per".equalsIgnoreCase(info.path("code").asText())) {
                            dto.setPer(parsePrice(info.get("value")));
                            break;
                        }
                    }
                }
            }

            // PBR 파싱
            if (root.has("pbr")) {
                dto.setPbr(parsePrice(root.get("pbr")));
            } else if (root.has("stockEndStockStatisticItemInfos")) {
                JsonNode infos = root.get("stockEndStockStatisticItemInfos");
                if (infos != null && infos.isArray()) {
                    for (JsonNode info : infos) {
                        if ("pbr".equalsIgnoreCase(info.path("code").asText())) {
                            dto.setPbr(parsePrice(info.get("value")));
                            break;
                        }
                    }
                }
            }

            // BPS 파싱 (PBR 계산용)
            if (root.has("bps")) {
                dto.setBps(parsePrice(root.get("bps")));
            }

            dto.setFetchedAt(DateTimeUtil.kstNow());
            dto.setDataSource("NAVER"); // 데이터 출처

            return dto;
        } catch (Exception e) {
            log.error("네이버 주식 상세 데이터 파싱 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 가격 문자열 파싱 (콤마 제거)
     */
    private BigDecimal parsePrice(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        String value = node.asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * JsonNode에서 문자열 값 추출
     */
    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "";
    }

    /**
     * JsonNode에서 여러 필드명 중 첫 번째로 존재하는 값 반환
     */
    private String getFirstAvailableText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                String value = node.get(field).asText();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 가격 이상치 진단 (현재가가 실거래가의 정수배로 표시되는 버그 추적).
     *
     * <p>현재가는 정상적으로 당일 저가~고가 범위 안에 있어야 한다. 현재가만 비정상 배수면
     * 이 범위를 벗어나므로 즉시 탐지된다. 모든 가격 필드가 동일 배수면 KIS 응답 자체 문제로,
     * raw 필드를 로그에 남겨 원인을 확정할 수 있게 한다. <b>가격은 보정하지 않는다</b> —
     * 잘못 보정하면 정상 종목을 훼손하므로 탐지·경고만 수행.
     */
    private void warnIfPriceOutlier(String stockCode, JsonNode output, StockPriceDto dto) {
        try {
            BigDecimal cur = dto.getCurrentPrice();
            if (cur == null || cur.compareTo(BigDecimal.ZERO) <= 0) return;

            String raw = String.format(
                    "stck_prpr=%s, stck_hgpr=%s, stck_lwpr=%s, stck_sdpr=%s, prdy_ctrt=%s",
                    getTextValue(output, "stck_prpr"), getTextValue(output, "stck_hgpr"),
                    getTextValue(output, "stck_lwpr"), getTextValue(output, "stck_sdpr"),
                    getTextValue(output, "prdy_ctrt"));

            // 세 그물 중 하나라도 발화하면 아래에서 UN vs J raw 를 1회 동시 조회해 원인 분류 로깅.
            boolean outlierDetected = false;

            // [그물 1] 당일 밴드 — 현재가만 오염된 경우를 잡는다.
            //   ※ ×10 이 응답 전체(현재가·고가·저가)에 일괄로 걸리면 현재가가 [저가,고가] 안에
            //     그대로 머물러 이 체크로는 절대 안 잡힌다. 그래서 그물 2·3 을 함께 둔다.
            BigDecimal high = dto.getHighPrice();
            BigDecimal low = dto.getLowPrice();
            boolean haveBand = high != null && low != null
                    && high.compareTo(BigDecimal.ZERO) > 0 && low.compareTo(BigDecimal.ZERO) > 0
                    && high.compareTo(low) >= 0;
            if (haveBand) {
                BigDecimal lowBound = low.multiply(new BigDecimal("0.9"));
                BigDecimal highBound = high.multiply(new BigDecimal("1.1"));
                if (cur.compareTo(lowBound) < 0 || cur.compareTo(highBound) > 0) {
                    outlierDetected = true;
                    log.error("[가격이상] {} 현재가 {} 가 당일 [{}~{}] 범위 밖 — 현재가 단독 오염 의심. KIS raw: {}",
                            stockCode, cur, low, high, raw);
                }
            }

            // [그물 2] 전일대비율(prdy_ctrt) 상한 — KIS 가 직접 계산한 등락률이 KRX 일일 변동제한(±30%)을
            //   크게 넘으면 응답 자체가 깨졌다는 신호. (단, 신규상장 첫날은 정상적으로 초과 가능 → 로깅만)
            BigDecimal ctrt = dto.getChangeRate();
            if (ctrt != null && ctrt.abs().compareTo(new BigDecimal("31")) > 0) {
                outlierDetected = true;
                log.error("[가격이상] {} 전일대비율 {}% 가 일일 변동제한(±30%) 초과 — 배수/필드 오염 또는 신규상장 확인. KIS raw: {}",
                        stockCode, ctrt, raw);
            }

            // [그물 3] DB 앵커 배수 — 일괄 스케일링을 잡는 유일하게 견고한 그물.
            //   응답 전체가 ×10 이어도 직전에 저장된(정상) DB 가격과 비교하면 비율이 ~10 으로 튄다.
            //   임계 5배/0.2배: 6 연속 상한가도 불가능한 수준이라 정상 등락 오탐 없음.
            //   (신규상장 공모가 대비 최대 4배 밴드보다도 위 → IPO 첫날도 안전)
            try {
                java.util.Optional<BigDecimal> prevOpt = stockPriceRepository
                        .findTopByStockCodeOrderByFetchedAtDesc(stockCode)
                        .map(StockPrice::getCurrentPrice)
                        .filter(prev -> prev != null && prev.compareTo(BigDecimal.ZERO) > 0);
                if (prevOpt.isPresent()) {
                    BigDecimal prev = prevOpt.get();
                    BigDecimal ratio = cur.divide(prev, 2, java.math.RoundingMode.HALF_UP);
                    if (ratio.compareTo(new BigDecimal("5")) >= 0
                            || ratio.compareTo(new BigDecimal("0.2")) <= 0) {
                        outlierDetected = true;
                        log.error("[가격이상] {} 현재가 {} 가 직전 저장가 {} 의 {}배 — 응답 일괄 배수 오염 강력 의심. KIS raw: {}",
                                stockCode, cur, prev, ratio, raw);
                    }
                }
            } catch (Exception ignore) {
                // DB 조회 실패는 진단 보조일 뿐 — 본 시세 흐름에 영향 주지 않음
            }

            // [원인 분류] 위 그물 중 하나라도 발화 → UN(통합, 시세 경로가 쓰는 값) vs J(KRX 단독) raw 동시 조회 로깅.
            //   UN 만 배수가 튀고 J 는 정상이면 통합시세(UN) 파싱/필드 규약 차이로 확정. 이상치 종목에서만 1회
            //   호출되므로 정상 경로 rate-limit 영향 없음. 보정·캐시·저장은 하지 않는다(로깅 전용, 불변식 유지).
            if (outlierDetected) {
                logUnVsJDiagnostic(stockCode, raw);
            }
        } catch (Exception e) {
            log.debug("[가격이상] {} 진단 중 오류(무시): {}", stockCode, e.getMessage());
        }
    }

    /**
     * ×10 배수오염 원인 분류용 — UN(getStockPrice 가 쓰는 통합시세) vs J(KRX 단독) 의 raw 필드
     * (stck_prpr/hgpr/lwpr/sdpr/prdy_ctrt)를 나란히 ERROR 로깅한다.
     *
     * <p>판독: UN 만 배수가 튀고 J 는 정상 → 통합시세(UN) 필드 규약/파싱 차이로 확정. 둘 다 같으면 KIS 원천
     * 데이터 문제. J 응답이 비면(rt_cd≠0/output 없음) NXT 전용·KRX 미상장 신호 자체가 단서.
     * <b>로깅만 — 보정/캐시/저장 없음.</b> 조회 실패는 본 시세 흐름에 영향 주지 않도록 모두 흡수.
     */
    private void logUnVsJDiagnostic(String stockCode, String unRaw) {
        try {
            JsonNode jResp = kisService.getStockPriceByMarketDiv(stockCode, "J");
            if (jResp == null) {
                log.error("[가격이상-진단] {} UN vs J 대조 — J(KRX단독) 응답 없음(NXT 전용/미상장 가능). UN raw: {}",
                        stockCode, unRaw);
                return;
            }
            String jRtCd = jResp.has("rt_cd") ? jResp.get("rt_cd").asText() : "";
            JsonNode jOut = jResp.get("output");
            if (!"0".equals(jRtCd) || jOut == null) {
                String jMsg = jResp.has("msg1") ? jResp.get("msg1").asText() : "";
                log.error("[가격이상-진단] {} UN vs J 대조 — J 응답 오류(rt_cd={}, msg={}). UN raw: {}",
                        stockCode, jRtCd, jMsg, unRaw);
                return;
            }
            String jRaw = String.format(
                    "stck_prpr=%s, stck_hgpr=%s, stck_lwpr=%s, stck_sdpr=%s, prdy_ctrt=%s",
                    getTextValue(jOut, "stck_prpr"), getTextValue(jOut, "stck_hgpr"),
                    getTextValue(jOut, "stck_lwpr"), getTextValue(jOut, "stck_sdpr"),
                    getTextValue(jOut, "prdy_ctrt"));
            log.error("[가격이상-진단] {} UN vs J raw 대조 → UN[{}] | J[{}]", stockCode, unRaw, jRaw);
        } catch (Exception e) {
            log.warn("[가격이상-진단] {} UN/J 대조 조회 실패(무시): {}", stockCode, e.getMessage());
        }
    }

    /**
     * JsonNode에서 BigDecimal 값 추출
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (!node.has(field)) {
            return BigDecimal.ZERO;
        }
        String value = node.get(field).asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 캐시 유효성 확인
     */
    private boolean isValidCache(StockPriceDto dto, int minutes) {
        if (dto == null || dto.getFetchedAt() == null) {
            return false;
        }
        return dto.getFetchedAt().isAfter(DateTimeUtil.kstNow().minusMinutes(minutes));
    }

    /**
     * Entity -> DTO 변환
     */
    private StockPriceDto entityToDto(StockPrice entity) {
        StockPriceDto dto = new StockPriceDto();
        dto.setStockCode(entity.getStockCode());
        dto.setStockName(entity.getStockName());
        dto.setCurrentPrice(entity.getCurrentPrice());
        dto.setOpenPrice(entity.getOpenPrice());
        dto.setHighPrice(entity.getHighPrice());
        dto.setLowPrice(entity.getLowPrice());
        dto.setChangeRate(entity.getChangeRate());
        dto.setVolume(entity.getVolume());
        dto.setFetchedAt(entity.getFetchedAt());
        return dto;
    }

    /**
     * DTO -> Entity 변환
     */
    private StockPrice dtoToEntity(StockPriceDto dto) {
        StockPrice entity = new StockPrice();
        entity.setStockCode(dto.getStockCode());
        entity.setStockName(dto.getStockName());
        entity.setCurrentPrice(dto.getCurrentPrice());
        entity.setOpenPrice(dto.getOpenPrice());
        entity.setHighPrice(dto.getHighPrice());
        entity.setLowPrice(dto.getLowPrice());
        entity.setChangeRate(dto.getChangeRate());
        entity.setVolume(dto.getVolume());
        entity.setFetchedAt(dto.getFetchedAt());
        return entity;
    }

    /**
     * 현재 사용 중인 API 소스 확인
     */
    public String getCurrentApiSource() {
        return kisService.isConfigured() ? "한국투자증권 (실시간)" : "네이버 증권 (15분 지연)";
    }

    /**
     * 특정 기간 동안의 거래대금 조회
     * @param stockCode 종목코드
     * @param minutes 조회 기간 (분) - 5, 30 등
     * @return 해당 기간 거래대금 (null이면 데이터 없음)
     */
    public BigDecimal getTradingValueForMinutes(String stockCode, int minutes) {
        if (!kisService.isConfigured()) {
            return null; // 분봉 데이터는 한투 API만 지원
        }

        try {
            JsonNode response = kisService.getStockMinuteChart(stockCode);
            if (response == null) {
                return null;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                return null;
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray()) {
                return null;
            }

            BigDecimal totalTradingValue = BigDecimal.ZERO;
            int count = 0;
            int maxCount = minutes; // 1분봉 기준으로 minutes개 수집

            // output2는 최신 데이터가 먼저 옴
            for (JsonNode item : output2) {
                if (count >= maxCount) break;

                BigDecimal price = getBigDecimalValue(item, "stck_prpr"); // 현재가
                BigDecimal volume = getBigDecimalValue(item, "cntg_vol"); // 체결거래량

                if (price != null && volume != null) {
                    BigDecimal tradingValue = price.multiply(volume);
                    totalTradingValue = totalTradingValue.add(tradingValue);
                }
                count++;
            }

            return totalTradingValue;

        } catch (Exception e) {
            log.error("분봉 거래대금 조회 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    // ========== Rate Limit 설정 (순차 처리) ==========
    // KIS API 요청 간 간격 (ms): 200 (초당 5건 이내로 제한)
    private static final int REQUEST_DELAY_MS = 200;
    // 네이버 API 요청 간 간격 (ms): 500 (초당 2건 - 409 방지)
    private static final int NAVER_REQUEST_DELAY_MS = 150;
    // Rate Limit 에러 시 재시도 대기 시간 (ms)
    private static final int RATE_LIMIT_RETRY_DELAY_MS = 500;

    /**
     * [개선] 여러 종목의 분봉 거래대금 일괄 조회 (캐시 + Rate Limit 제어)
     *
     * @param stockCodes 종목코드 리스트
     * @param minutes    조회 기간 (분) - 5, 30 등
     * @return 종목코드 -> 거래대금 Map
     */
    public Map<String, BigDecimal> getTradingValueForMinutesBatch(List<String> stockCodes, int minutes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return new HashMap<>();
        }

        if (!kisService.isConfigured()) {
            log.warn("분봉 데이터 배치 조회 불가 - 한투 API 미설정");
            return new HashMap<>();
        }

        long startTime = System.currentTimeMillis();
        Map<String, BigDecimal> result = new ConcurrentHashMap<>();

        // 1. 캐시에서 먼저 조회
        List<String> missingCodes = new ArrayList<>();
        for (String code : stockCodes) {
            String cacheKey = code + "_" + minutes;
            MinuteTradingCache cached = minuteTradingCache.get(cacheKey);
            if (cached != null && cached.isValid()) {
                result.put(code, cached.value);
            } else {
                missingCodes.add(code);
            }
        }

        int cacheHits = result.size();
        if (missingCodes.isEmpty()) {
            log.info("분봉 거래대금 - 전체 캐시 히트: {}, 기간: {}분, 소요: {}ms",
                    cacheHits, minutes, System.currentTimeMillis() - startTime);
            return result;
        }

        log.info("분봉 거래대금 배치 조회 - 캐시 히트: {}, API 조회: {}, 기간: {}분",
                cacheHits, missingCodes.size(), minutes);

        // 로그 카운터 리셋 (배치별로 처음 3개만 상세 로그)
        minuteApiLogCount.set(0);
        int apiSuccessCount = 0;

        // 순차 처리로 변경 (CompletableFuture + Thread.sleep 조합의 스레드풀 블로킹 문제 해결)
        for (int i = 0; i < missingCodes.size(); i++) {
            String stockCode = missingCodes.get(i);

            try {
                // Rate Limit 제어: 200ms 간격 (초당 5건)
                if (i > 0) {
                    Thread.sleep(REQUEST_DELAY_MS);
                }

                BigDecimal value = fetchMinuteTradingValueWithRetry(stockCode, minutes);

                if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                    result.put(stockCode, value);
                    minuteTradingCache.put(stockCode + "_" + minutes, new MinuteTradingCache(value));
                    apiSuccessCount++;
                } else {
                    result.put(stockCode, BigDecimal.ZERO);
                }

                // 20개마다 진행상황 로그
                if ((i + 1) % 20 == 0) {
                    log.debug("분봉 조회 진행 중: {}/{}, 성공: {}", i + 1, missingCodes.size(), apiSuccessCount);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("분봉 조회 중단됨 (interrupt)");
                break;
            } catch (Exception e) {
                log.warn("분봉 조회 실패 [{}]: {}", stockCode, e.getMessage());
                result.put(stockCode, BigDecimal.ZERO);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("분봉 거래대금 배치 조회 완료 - 캐시: {}, API 성공: {}/{}, 총: {}, 소요: {}ms",
                cacheHits, apiSuccessCount, missingCodes.size(), result.size(), elapsed);

        return result;
    }

    // Rate Limit 발생 여부 추적용 ThreadLocal
    private static final ThreadLocal<Boolean> wasRateLimited = ThreadLocal.withInitial(() -> false);

    /**
     * 분봉 거래대금 단건 조회 (Rate Limit 에러 시만 1회 재시도)
     */
    private BigDecimal fetchMinuteTradingValueWithRetry(String stockCode, int minutes) {
        wasRateLimited.set(false);
        BigDecimal result = fetchMinuteTradingValueInternal(stockCode, minutes);

        // Rate Limit 에러인 경우에만 재시도 (빈 응답은 재시도해도 같은 결과)
        if (result == null && wasRateLimited.get()) {
            try {
                log.debug("Rate Limit 재시도 대기 [{}] - {}ms", stockCode, RATE_LIMIT_RETRY_DELAY_MS);
                Thread.sleep(RATE_LIMIT_RETRY_DELAY_MS);
                wasRateLimited.set(false);
                result = fetchMinuteTradingValueInternal(stockCode, minutes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return result;
    }

    // 분봉 API 에러 로깅용 카운터 (처음 3개만 상세 로그)
    private static final java.util.concurrent.atomic.AtomicInteger minuteApiLogCount = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 분봉 거래대금 실제 조회 (내부용)
     */
    private BigDecimal fetchMinuteTradingValueInternal(String stockCode, int minutes) {
        try {
            JsonNode response = kisService.getStockMinuteChart(stockCode);
            if (response == null) {
                if (minuteApiLogCount.incrementAndGet() <= 3) {
                    log.warn("분봉 API 응답 null [{}] - KIS API 호출 실패", stockCode);
                }
                return null;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText("") : "";
            String msgCd = response.has("msg_cd") ? response.get("msg_cd").asText("") : "";
            String msg1 = response.has("msg1") ? response.get("msg1").asText("") : "";

            // 응답이 비어있으면 디버그 로그 (처음 1개만)
            if (rtCd.isEmpty() && msgCd.isEmpty() && minuteApiLogCount.get() == 0) {
                log.warn("분봉 API 비정상 응답 [{}]: {}", stockCode, response.toString().substring(0, Math.min(200, response.toString().length())));
            }

            // Rate Limit 에러 체크 (EGW00201: 초당 거래건수 초과)
            if ("EGW00201".equals(msgCd)) {
                log.warn("Rate Limit 초과 [{}] - 재시도 예정", stockCode);
                wasRateLimited.set(true);
                return null;
            }

            // rt_cd가 비어있거나 0이 아니면 에러
            if (rtCd.isEmpty() || !"0".equals(rtCd)) {
                if (minuteApiLogCount.incrementAndGet() <= 3) {
                    log.warn("분봉 API 에러 [{}]: rt_cd={}, msg_cd={}, msg={}", stockCode, rtCd, msgCd, msg1);
                }
                return null;
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray() || output2.size() == 0) {
                if (minuteApiLogCount.incrementAndGet() <= 3) {
                    log.warn("분봉 데이터 없음 [{}]: output2가 비어있음", stockCode);
                }
                return null;
            }

            BigDecimal totalTradingValue = BigDecimal.ZERO;
            int count = 0;
            int maxCount = minutes;

            for (JsonNode item : output2) {
                if (count >= maxCount) break;

                BigDecimal price = getBigDecimalValue(item, "stck_prpr");
                BigDecimal volume = getBigDecimalValue(item, "cntg_vol");

                if (price != null && volume != null) {
                    totalTradingValue = totalTradingValue.add(price.multiply(volume));
                }
                count++;
            }

            return totalTradingValue;

        } catch (Exception e) {
            log.debug("분봉 거래대금 파싱 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

}
