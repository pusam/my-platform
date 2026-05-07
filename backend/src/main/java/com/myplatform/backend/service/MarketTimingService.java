package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.MarketTimingDto.*;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 시장 타이밍 분석 서비스
 * - ADR (등락비율) 계산
 * - 시장 상태 판단
 * - 데이터 수집 (네이버 금융)
 */
@Service
@Slf4j
public class MarketTimingService {

    private final MarketDailyStatusRepository marketDailyStatusRepository;
    private final TelegramNotificationService telegramNotificationService;
    private final RedisCacheService redisCacheService;

    // 시장 상태 Redis L2 캐시 — 60초 폴링 부담 완화
    private static final String CACHE_MARKET_STATUS = "marketStatus";
    private static final String KEY_CURRENT = "current";
    private static final Duration TTL_MARKET_STATUS = Duration.ofMinutes(2);

    public MarketTimingService(MarketDailyStatusRepository marketDailyStatusRepository,
                               TelegramNotificationService telegramNotificationService,
                               RedisCacheService redisCacheService) {
        this.marketDailyStatusRepository = marketDailyStatusRepository;
        this.telegramNotificationService = telegramNotificationService;
        this.redisCacheService = redisCacheService;
    }

    // ADR 기준값
    // ADR 임계점 — 120 이상 과열, 80 이하 공포. NORMAL 구간은 두 임계 사이.
    private static final BigDecimal ADR_OVERHEATED = new BigDecimal("120");
    private static final BigDecimal ADR_OVERSOLD = new BigDecimal("80");
    private static final BigDecimal ADR_EXTREME_FEAR = new BigDecimal("60");

    private static final int ADR_PERIOD = 20;  // ADR 계산 기간

    // === KRX API 설정 ===
    private static final String KRX_OTP_URL = "http://data.krx.co.kr/comm/bldAttendant/getOtp.cmd";
    private static final String KRX_DATA_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final String KRX_MARKET_STATUS_BLD = "dbms/MDC/STAT/standard/MDCSTAT00101";  // 전체 시장 현황
    private static final String KRX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // === 네이버 모바일 API (지수 폴백) ===
    private static final String NAVER_INDEX_API = "https://m.stock.naver.com/api/index/%s/basic";
    private static final String NAVER_INDEX_REFERER = "https://m.stock.naver.com/";
    private static final String NAVER_INDEX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    /**
     * 서버 시작 시 오늘 ADR 데이터가 없으면 자동 수집
     * - 45초 지연으로 다른 초기화 작업과 리소스 경합 방지
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void initializeDataIfEmpty() {
        try {
            Thread.sleep(45000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        LocalDate today = LocalDate.now();

        // 주말이면 금요일 날짜 사용
        java.time.DayOfWeek dayOfWeek = today.getDayOfWeek();
        if (dayOfWeek == java.time.DayOfWeek.SATURDAY) {
            today = today.minusDays(1);
        } else if (dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            today = today.minusDays(2);
        }

        // 오늘(또는 가장 최근 영업일) 데이터가 없으면 수집
        boolean kospiExists = marketDailyStatusRepository.findByMarketTypeAndTradeDate("KOSPI", today).isPresent();
        boolean kosdaqExists = marketDailyStatusRepository.findByMarketTypeAndTradeDate("KOSDAQ", today).isPresent();

        if (!kospiExists || !kosdaqExists) {
            log.info("ADR 시장 데이터가 없습니다. 초기 데이터 수집을 시작합니다... (날짜: {})", today);
            try {
                collectMarketData();
                log.info("ADR 시장 데이터 초기 수집 완료");
            } catch (Exception e) {
                log.warn("ADR 시장 데이터 초기 수집 실패 (나중에 스케줄러가 재시도): {}", e.getMessage());
            }
        } else {
            log.info("ADR 시장 데이터 존재 확인됨 (날짜: {})", today);
        }
    }

    /**
     * 현재 시장 타이밍 분석 — Redis L2 캐시 우선 (TTL 2분).
     * 워머({@code MarketCacheWarmerService.warmMarketStatus})가 60초 마다 직접 채워두므로
     * 프론트 60초 폴링은 대부분 캐시 hit. miss 시 즉시 계산 후 반환(저장하지 않음 — 워머가 마스터).
     */
    public MarketTimingDto getCurrentMarketTiming() {
        MarketTimingDto cached = redisCacheService.get(
                CACHE_MARKET_STATUS, KEY_CURRENT,
                new TypeReference<MarketTimingDto>() {});
        if (cached != null) {
            return cached;
        }
        return computeCurrentMarketTiming();
    }

    /**
     * 워머 전용 — 외부 소스에서 새로 계산 후 Redis 갱신.
     */
    public void refreshMarketTimingToCache() {
        try {
            MarketTimingDto fresh = computeCurrentMarketTiming();
            if (fresh != null) {
                redisCacheService.put(CACHE_MARKET_STATUS, KEY_CURRENT, fresh, TTL_MARKET_STATUS);
            }
        } catch (Exception e) {
            log.warn("[MarketTiming] 캐시 갱신 실패: {}", e.getMessage());
        }
    }

    private MarketTimingDto computeCurrentMarketTiming() {
        log.info("시장 타이밍 분석 시작");

        // 최신 데이터 조회
        List<MarketDailyStatus> latestData = marketDailyStatusRepository.findLatestAll();

        if (latestData.isEmpty()) {
            log.warn("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.");
            return createEmptyTiming();
        }

        LocalDate analysisDate = latestData.get(0).getTradeDate();

        // ★ Freshness check: 데이터 경과일 계산
        long dataAgeDays = java.time.temporal.ChronoUnit.DAYS.between(analysisDate, LocalDate.now());
        Integer dataAge = null;
        if (dataAgeDays > 0) {
            log.info("DB 최신 데이터가 과거임 ({}), {}일 전 데이터, 실시간 지수로 보충 예정", analysisDate, dataAgeDays);
            dataAge = (int) dataAgeDays;
            if (dataAgeDays >= 2) {
                log.warn("[MarketTiming] ⚠ 시장 데이터 오래됨! 최신 거래일: {} ({}일 전) - ADR 분석 신뢰도 저하", analysisDate, dataAgeDays);
            }
        }

        // 코스피/코스닥 데이터 분리
        MarketStatusDto kospiStatus = null;
        MarketStatusDto kosdaqStatus = null;

        for (MarketDailyStatus data : latestData) {
            MarketStatusDto status = convertToStatusDto(data);
            if ("KOSPI".equals(data.getMarketType())) {
                kospiStatus = status;
            } else if ("KOSDAQ".equals(data.getMarketType())) {
                kosdaqStatus = status;
            }
        }

        // 지수/등락률이 없으면 실시간 네이버 API로 보충
        kospiStatus = refreshIndexIfMissing(kospiStatus, "KOSPI");
        kosdaqStatus = refreshIndexIfMissing(kosdaqStatus, "KOSDAQ");

        // ADR이 없으면 계산
        if (kospiStatus != null && kospiStatus.getAdr20() == null) {
            BigDecimal adr = calculateAdr("KOSPI", analysisDate);
            kospiStatus.setAdr20(adr);
            kospiStatus.setCondition(determineCondition(adr));
        }
        if (kosdaqStatus != null && kosdaqStatus.getAdr20() == null) {
            BigDecimal adr = calculateAdr("KOSDAQ", analysisDate);
            kosdaqStatus.setAdr20(adr);
            kosdaqStatus.setCondition(determineCondition(adr));
        }

        // ★ 당일 등락비가 없으면 실시간 크롤링으로 보충 (지수와 시점 동기화)
        refreshDailyRatioIfMissing(kospiStatus, "KOSPI");
        refreshDailyRatioIfMissing(kosdaqStatus, "KOSDAQ");

        // 종합 ADR 계산
        BigDecimal combinedAdr = calculateCombinedAdr(kospiStatus, kosdaqStatus);
        MarketCondition overallCondition = determineCondition(combinedAdr);

        // 진단 및 전략 생성
        String diagnosis = generateDiagnosis(kospiStatus, kosdaqStatus, combinedAdr);
        String strategy = overallCondition != null ? overallCondition.getSuggestion() : "";

        // ★ 당일 급락/폭락 오버라이드: ADR이 정상이더라도 당일 등락률이 크면 상태 보정
        BigDecimal kospiRate = (kospiStatus != null) ? kospiStatus.getIndexChangeRate() : null;
        BigDecimal kosdaqRate = (kosdaqStatus != null) ? kosdaqStatus.getIndexChangeRate() : null;
        BigDecimal worstRate = null;
        if (kospiRate != null && kosdaqRate != null) {
            worstRate = kospiRate.min(kosdaqRate);
        } else if (kospiRate != null) {
            worstRate = kospiRate;
        } else if (kosdaqRate != null) {
            worstRate = kosdaqRate;
        }

        boolean crashOverride = false;
        boolean dropOverride = false;
        StringBuilder overrideReasons = new StringBuilder();

        // -3% 이하: 폭락/패닉
        if (kospiRate != null && kospiRate.compareTo(new BigDecimal("-3.0")) <= 0) {
            crashOverride = true;
            overrideReasons.append(String.format("KOSPI %+.2f%% 폭락", kospiRate));
        }
        if (kosdaqRate != null && kosdaqRate.compareTo(new BigDecimal("-3.0")) <= 0) {
            if (crashOverride) overrideReasons.append(" + ");
            crashOverride = true;
            overrideReasons.append(String.format("KOSDAQ %+.2f%% 폭락", kosdaqRate));
        }

        // -2% 이하 (양쪽 모두 또는 한쪽 -2.5% 이하): 급락 → 침체 이상으로 보정
        if (!crashOverride && worstRate != null) {
            boolean bothDown2 = kospiRate != null && kosdaqRate != null
                    && kospiRate.compareTo(new BigDecimal("-2.0")) <= 0
                    && kosdaqRate.compareTo(new BigDecimal("-2.0")) <= 0;
            boolean oneDown25 = worstRate.compareTo(new BigDecimal("-2.5")) <= 0;

            if (bothDown2 || oneDown25) {
                dropOverride = true;
                if (kospiRate != null && kospiRate.compareTo(new BigDecimal("-2.0")) <= 0) {
                    overrideReasons.append(String.format("KOSPI %+.2f%%", kospiRate));
                }
                if (kosdaqRate != null && kosdaqRate.compareTo(new BigDecimal("-2.0")) <= 0) {
                    if (overrideReasons.length() > 0) overrideReasons.append(" + ");
                    overrideReasons.append(String.format("KOSDAQ %+.2f%%", kosdaqRate));
                }
            }
        }

        if (crashOverride) {
            overallCondition = MarketCondition.CRASH;
            diagnosis = String.format("폭락/패닉 — %s. 관망 및 하방 리스크 관리 필수.", overrideReasons);
            strategy = MarketCondition.CRASH.getSuggestion();
            log.warn("[시장 타이밍] 폭락 오버라이드 발동: {}", overrideReasons);
        } else if (dropOverride) {
            // ADR이 정상이더라도 당일 급락 시 최소 OVERSOLD로 보정
            if (overallCondition == MarketCondition.NORMAL || overallCondition == MarketCondition.OVERHEATED) {
                overallCondition = MarketCondition.OVERSOLD;
            }
            diagnosis = String.format("당일 급락 — %s. %s", overrideReasons, overallCondition.getSuggestion());
            strategy = overallCondition.getSuggestion();
            log.warn("[시장 타이밍] 급락 보정 발동: {} → {}", overrideReasons, overallCondition);
        }

        return MarketTimingDto.builder()
                .analysisDate(analysisDate)
                .kospi(kospiStatus)
                .kosdaq(kosdaqStatus)
                .combinedAdr(combinedAdr)
                .overallCondition(overallCondition)
                .diagnosis(diagnosis)
                .strategy(strategy)
                .dataAge(dataAge)
                .build();
    }

    /**
     * ADR 히스토리 조회 (차트용)
     */
    public List<AdrHistoryDto> getAdrHistory(int days) {
        List<AdrHistoryDto> history = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days + ADR_PERIOD);

        List<MarketDailyStatus> kospiData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSPI", startDate, endDate);
        List<MarketDailyStatus> kosdaqData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSDAQ", startDate, endDate);

        // 날짜별로 매칭
        for (int i = 0; i < Math.min(days, kospiData.size()); i++) {
            MarketDailyStatus kospi = kospiData.get(i);
            LocalDate date = kospi.getTradeDate();

            BigDecimal kospiAdr = kospi.getAdr20() != null ? kospi.getAdr20() : calculateAdr("KOSPI", date);
            BigDecimal kosdaqAdr = null;

            // 코스닥 데이터 찾기
            for (MarketDailyStatus k : kosdaqData) {
                if (k.getTradeDate().equals(date)) {
                    kosdaqAdr = k.getAdr20() != null ? k.getAdr20() : calculateAdr("KOSDAQ", date);
                    break;
                }
            }

            BigDecimal combined = calculateCombinedAdrFromValues(kospiAdr, kosdaqAdr);

            history.add(AdrHistoryDto.builder()
                    .date(date)
                    .kospiAdr(kospiAdr)
                    .kosdaqAdr(kosdaqAdr)
                    .combinedAdr(combined)
                    .build());
        }

        return history;
    }

    /**
     * 매일 장 마감 후 ADR 시장 지표 자동 수집 (평일 16:30)
     * - 15:30 장 마감 후 1시간 여유를 두고 수집
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void scheduledMarketDataCollection() {
        log.info("=== ADR 시장 지표 자동 수집 시작 (16:30) ===");
        try {
            collectMarketData();
            log.info("=== ADR 시장 지표 자동 수집 완료 ===");
        } catch (Exception e) {
            log.error("ADR 시장 지표 자동 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 시장 데이터 수집 (네이버 금융)
     */
    @Transactional
    public void collectMarketData() {
        log.info("시장 데이터 수집 시작");

        try {
            // 코스피 데이터 수집
            collectMarketDataFromNaver("KOSPI");

            // 코스닥 데이터 수집
            collectMarketDataFromNaver("KOSDAQ");

            log.info("시장 데이터 수집 완료");
        } catch (Exception e) {
            log.error("시장 데이터 수집 실패: {}", e.getMessage(), e);
            throw new RuntimeException("시장 데이터 수집 실패: " + e.getMessage());
        }
    }

    /**
     * 네이버 금융에서 시장 데이터 수집
     */
    private void collectMarketDataFromNaver(String marketType) {
        try {
            String url;
            if ("KOSPI".equals(marketType)) {
                url = "https://finance.naver.com/sise/sise_rise.naver";
            } else {
                url = "https://finance.naver.com/sise/sise_rise.naver?sosok=1";
            }

            // 상승/하락/보합 종목 수 수집
            int advancingCount = crawlStockCount(marketType, "rise");
            int decliningCount = crawlStockCount(marketType, "fall");
            int unchangedCount = crawlStockCount(marketType, "steady");
            int upperLimitCount = crawlStockCount(marketType, "upper");
            int lowerLimitCount = crawlStockCount(marketType, "lower");

            // 지수 정보 수집
            BigDecimal[] indexInfo = crawlIndexInfo(marketType);
            BigDecimal indexClose = indexInfo[0];
            BigDecimal indexChangeRate = indexInfo[1];
            BigDecimal tradingValue = indexInfo[2];

            LocalDate today = LocalDate.now();

            // 기존 데이터 확인
            Optional<MarketDailyStatus> existing = marketDailyStatusRepository
                    .findByMarketTypeAndTradeDate(marketType, today);

            MarketDailyStatus status;
            if (existing.isPresent()) {
                status = existing.get();
            } else {
                status = new MarketDailyStatus();
                status.setMarketType(marketType);
                status.setTradeDate(today);
            }

            status.setAdvancingCount(advancingCount);
            status.setDecliningCount(decliningCount);
            status.setUnchangedCount(unchangedCount);
            status.setUpperLimitCount(upperLimitCount);
            status.setLowerLimitCount(lowerLimitCount);
            status.setTotalCount(advancingCount + decliningCount + unchangedCount);
            status.setIndexClose(indexClose);
            status.setIndexChangeRate(indexChangeRate);
            status.setTradingValue(tradingValue);

            // 당일 등락비 계산
            if (decliningCount > 0) {
                BigDecimal dailyRatio = BigDecimal.valueOf(advancingCount)
                        .divide(BigDecimal.valueOf(decliningCount), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                status.setDailyRatio(dailyRatio);
            }

            // ADR 계산
            BigDecimal adr = calculateAdr(marketType, today);
            status.setAdr20(adr);

            marketDailyStatusRepository.save(status);
            log.info("{} 시장 데이터 저장 완료: 상승={}, 하락={}, 보합={}, ADR={}",
                    marketType, advancingCount, decliningCount, unchangedCount, adr);

        } catch (Exception e) {
            log.error("{} 시장 데이터 수집 실패: {}", marketType, e.getMessage());
            throw new RuntimeException(marketType + " 데이터 수집 실패: " + e.getMessage());
        }
    }

    /**
     * 종목 수 크롤링
     */
    private int crawlStockCount(String marketType, String type) {
        try {
            String sosok = "KOSPI".equals(marketType) ? "0" : "1";
            String url;

            switch (type) {
                case "rise":
                    url = "https://finance.naver.com/sise/sise_rise.naver?sosok=" + sosok;
                    break;
                case "fall":
                    url = "https://finance.naver.com/sise/sise_fall.naver?sosok=" + sosok;
                    break;
                case "steady":
                    url = "https://finance.naver.com/sise/sise_steady.naver?sosok=" + sosok;
                    break;
                case "upper":
                    url = "https://finance.naver.com/sise/sise_upper.naver?sosok=" + sosok;
                    break;
                case "lower":
                    url = "https://finance.naver.com/sise/sise_lower.naver?sosok=" + sosok;
                    break;
                default:
                    return 0;
            }

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            // 종목 수 추출 (예: "상승 (528)")
            Element countElement = doc.selectFirst("div.subtop_sise_graph2 span");
            if (countElement != null) {
                String text = countElement.text();
                // 괄호 안의 숫자 추출
                if (text.contains("(") && text.contains(")")) {
                    String numStr = text.substring(text.indexOf("(") + 1, text.indexOf(")"));
                    return Integer.parseInt(numStr.replace(",", ""));
                }
            }

            // 테이블에서 행 수 세기 (fallback)
            Elements rows = doc.select("table.type_2 tbody tr");
            int count = 0;
            for (Element row : rows) {
                if (row.select("td").size() > 1 && !row.select("td a").isEmpty()) {
                    count++;
                }
            }
            return count;

        } catch (Exception e) {
            log.warn("{} {} 종목 수 크롤링 실패: {}", marketType, type, e.getMessage());
            return 0;
        }
    }

    /**
     * 지수 정보 조회 (네이버 모바일 API 우선 → HTML 크롤링 폴백)
     */
    private BigDecimal[] crawlIndexInfo(String marketType) {
        BigDecimal[] result = new BigDecimal[3];  // [종가, 등락률, 거래대금]

        // 1차: 네이버 모바일 API (JSON - 안정적)
        try {
            result = fetchIndexFromNaverApi(marketType);
            if (result[0] != null && result[1] != null) {
                log.debug("{} 지수 네이버 API 성공: 종가={}, 등락률={}%", marketType, result[0], result[1]);
                return result;
            }
        } catch (Exception e) {
            log.debug("{} 네이버 API 실패, HTML 폴백 시도: {}", marketType, e.getMessage());
        }

        // 2차: HTML 크롤링 폴백
        try {
            String url = "KOSPI".equals(marketType)
                    ? "https://finance.naver.com/sise/sise_index.naver?code=KOSPI"
                    : "https://finance.naver.com/sise/sise_index.naver?code=KOSDAQ";

            Document doc = Jsoup.connect(url)
                    .userAgent(NAVER_INDEX_USER_AGENT)
                    .timeout(10000)
                    .get();

            // 지수 종가
            Element indexElement = doc.selectFirst("#now_value");
            if (indexElement != null) {
                result[0] = new BigDecimal(indexElement.text().replace(",", ""));
            }

            // 등락률 - 여러 셀렉터 시도
            BigDecimal changeRate = parseChangeRateFromHtml(doc);
            if (changeRate != null) {
                result[1] = changeRate;
            }

            // 거래대금
            Element tradingElement = doc.selectFirst("em#quant");
            if (tradingElement != null) {
                String tradingText = tradingElement.text().replace(",", "").replace("억", "");
                result[2] = new BigDecimal(tradingText);
            }

            if (result[0] != null) {
                log.debug("{} 지수 HTML 크롤링 성공: 종가={}, 등락률={}", marketType, result[0], result[1]);
            }

        } catch (Exception e) {
            log.warn("{} 지수 정보 크롤링 실패: {}", marketType, e.getMessage());
        }

        return result;
    }

    /**
     * 네이버 모바일 API로 지수 정보 조회
     * API: https://m.stock.naver.com/api/index/{KOSPI|KOSDAQ}/basic
     * 응답 필드: closePrice, compareToPreviousClosePrice, fluctuationsRatio 등
     */
    private BigDecimal[] fetchIndexFromNaverApi(String marketType) throws Exception {
        BigDecimal[] result = new BigDecimal[3];
        String code = "KOSPI".equals(marketType) ? "KOSPI" : "KOSDAQ";

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", NAVER_INDEX_USER_AGENT);
        headers.set("Referer", NAVER_INDEX_REFERER);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        String url = String.format(NAVER_INDEX_API, code);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());

            // 종가 (closePrice)
            if (root.has("closePrice")) {
                String closeStr = root.get("closePrice").asText().replace(",", "");
                result[0] = new BigDecimal(closeStr);
            }

            // 등락률 (fluctuationsRatio)
            if (root.has("fluctuationsRatio")) {
                String ratioStr = root.get("fluctuationsRatio").asText().replace(",", "");
                result[1] = new BigDecimal(ratioStr);
            }

            // 거래대금 (accumulatedTradingValue - 백만원 단위 → 억원 변환)
            if (root.has("accumulatedTradingValue")) {
                try {
                    String tradingStr = root.get("accumulatedTradingValue").asText().replace(",", "");
                    BigDecimal tradingValue = new BigDecimal(tradingStr);
                    result[2] = tradingValue.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
                } catch (Exception e) {
                    log.debug("거래대금 파싱 실패: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * HTML에서 등락률 추출 (여러 셀렉터 시도)
     */
    private BigDecimal parseChangeRateFromHtml(Document doc) {
        // 1차: #change_rate (기존)
        Element changeElement = doc.selectFirst("#change_rate");
        if (changeElement != null) {
            try {
                String rateText = changeElement.text().replace("%", "").replace("+", "").trim();
                if (!rateText.isEmpty()) {
                    BigDecimal rate = new BigDecimal(rateText);
                    // 하락 여부 확인 (상위 요소에 "ndown" 또는 "minus" 클래스)
                    Element parent = changeElement.parent();
                    if (parent != null && (parent.classNames().contains("ndown") ||
                            parent.classNames().contains("minus") ||
                            parent.html().contains("ico_down"))) {
                        rate = rate.negate();
                    }
                    return rate;
                }
            } catch (NumberFormatException e) {
                log.debug("change_rate 파싱 실패: {}", changeElement.text());
            }
        }

        // 2차: .subtit_sise 내 등락률
        Element subtitElement = doc.selectFirst(".subtit_sise .change_rate");
        if (subtitElement != null) {
            try {
                String rateText = subtitElement.text().replace("%", "").replace("+", "").trim();
                if (!rateText.isEmpty()) {
                    return new BigDecimal(rateText);
                }
            } catch (NumberFormatException e) {
                log.debug("subtit_sise 등락률 파싱 실패");
            }
        }

        // 3차: 전일대비 + 종가로 계산
        Element changeValueElement = doc.selectFirst("#change_value_01");
        Element nowValueElement = doc.selectFirst("#now_value");
        if (changeValueElement != null && nowValueElement != null) {
            try {
                String changeStr = changeValueElement.text().replace(",", "").trim();
                String nowStr = nowValueElement.text().replace(",", "").trim();
                if (!changeStr.isEmpty() && !nowStr.isEmpty()) {
                    BigDecimal change = new BigDecimal(changeStr);
                    BigDecimal now = new BigDecimal(nowStr);
                    BigDecimal prev = now.subtract(change);
                    if (prev.compareTo(BigDecimal.ZERO) > 0) {
                        return change.divide(prev, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                }
            } catch (NumberFormatException e) {
                log.debug("전일대비 계산 폴백 실패");
            }
        }

        return null;
    }

    /**
     * DB에서 가져온 지수 데이터에 등락률이 없으면 실시간 네이버 API로 보충
     */
    private MarketStatusDto refreshIndexIfMissing(MarketStatusDto status, String marketType) {
        if (status == null) {
            // status 자체가 없으면 실시간으로 생성
            status = MarketStatusDto.builder()
                    .marketType(marketType)
                    .tradeDate(LocalDate.now())
                    .build();
        }

        // DB 데이터가 오늘이 아니면 무조건 갱신 (과거 데이터 방지)
        boolean isOldData = status.getTradeDate() != null && !status.getTradeDate().equals(LocalDate.now());
        // ★ 장중(09:00~15:30)에는 항상 실시간 갱신 (DB에 stale 데이터가 저장됐을 수 있음)
        LocalTime now = LocalTime.now();
        boolean isDuringMarketHours = !now.isBefore(LocalTime.of(9, 0)) && !now.isAfter(LocalTime.of(15, 30));
        boolean needsRefresh = isOldData
                || isDuringMarketHours
                || status.getIndexClose() == null
                || status.getIndexChangeRate() == null
                || status.getIndexChangeRate().compareTo(BigDecimal.ZERO) == 0;

        if (needsRefresh) {
            try {
                BigDecimal[] indexInfo = fetchIndexFromNaverApi(marketType);
                if (indexInfo[0] != null) {
                    status.setIndexClose(indexInfo[0]);
                }
                if (indexInfo[1] != null) {
                    status.setIndexChangeRate(indexInfo[1]);
                }
                if (indexInfo[2] != null) {
                    status.setTradingValue(indexInfo[2]);
                }
                log.info("{} 지수 실시간 보충 완료: 종가={}, 등락률={}%", marketType, indexInfo[0], indexInfo[1]);
            } catch (Exception e) {
                log.debug("{} 지수 실시간 보충 실패: {}", marketType, e.getMessage());
            }
        }

        return status;
    }

    /**
     * 당일 등락비가 없으면 실시간 크롤링으로 보충
     * - 지수는 실시간 API로 갱신되지만 등락비는 16:30 스케줄 수집에 의존
     * - 장중에는 DB에 오늘 데이터가 없으므로 등락비도 실시간으로 보충
     */
    private void refreshDailyRatioIfMissing(MarketStatusDto status, String marketType) {
        if (status == null || status.getDailyRatio() != null) {
            return;
        }
        try {
            int adv = crawlStockCount(marketType, "rise");
            int dec = crawlStockCount(marketType, "fall");
            if (dec > 0) {
                BigDecimal dailyRatio = BigDecimal.valueOf(adv)
                        .divide(BigDecimal.valueOf(dec), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                status.setDailyRatio(dailyRatio);
                // 당일 등락비 기준으로 condition도 갱신
                status.setCondition(determineCondition(dailyRatio));
                log.info("{} 당일 등락비 실시간 보충: {} (상승={}, 하락={})", marketType, dailyRatio, adv, dec);
            }
        } catch (Exception e) {
            log.debug("{} 당일 등락비 실시간 보충 실패: {}", marketType, e.getMessage());
        }
    }

    /**
     * ADR 계산 (20일 기준)
     * ADR = (20일 상승 종목 수 합계 / 20일 하락 종목 수 합계) * 100
     */
    public BigDecimal calculateAdr(String marketType, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(ADR_PERIOD + 10);  // 여유있게 조회

        List<MarketDailyStatus> recentData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(marketType, startDate, endDate);

        if (recentData.size() < ADR_PERIOD) {
            log.debug("{} ADR 계산 불가: 데이터 부족 ({}/{}일)", marketType, recentData.size(), ADR_PERIOD);
            return null;
        }

        // 최근 20일 데이터만 사용
        List<MarketDailyStatus> targetData = recentData.subList(0, Math.min(ADR_PERIOD, recentData.size()));

        long totalAdvancing = 0;
        long totalDeclining = 0;

        for (MarketDailyStatus data : targetData) {
            totalAdvancing += data.getAdvancingCount() != null ? data.getAdvancingCount() : 0;
            totalDeclining += data.getDecliningCount() != null ? data.getDecliningCount() : 0;
        }

        if (totalDeclining == 0) {
            return new BigDecimal("999.99");  // 하락 종목 없음
        }

        BigDecimal adr = BigDecimal.valueOf(totalAdvancing)
                .divide(BigDecimal.valueOf(totalDeclining), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        log.debug("{} ADR 계산 완료: {} (상승합계={}, 하락합계={})",
                marketType, adr, totalAdvancing, totalDeclining);

        return adr;
    }

    /**
     * 종합 ADR 계산
     */
    private BigDecimal calculateCombinedAdr(MarketStatusDto kospi, MarketStatusDto kosdaq) {
        if (kospi == null || kosdaq == null) {
            if (kospi != null) return kospi.getAdr20();
            if (kosdaq != null) return kosdaq.getAdr20();
            return null;
        }

        return calculateCombinedAdrFromValues(kospi.getAdr20(), kosdaq.getAdr20());
    }

    private BigDecimal calculateCombinedAdrFromValues(BigDecimal kospiAdr, BigDecimal kosdaqAdr) {
        if (kospiAdr == null && kosdaqAdr == null) return null;
        if (kospiAdr == null) return kosdaqAdr;
        if (kosdaqAdr == null) return kospiAdr;

        // 단순 평균
        return kospiAdr.add(kosdaqAdr)
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    }

    /**
     * ADR 기반 시장 상태 판단
     */
    private MarketCondition determineCondition(BigDecimal adr) {
        if (adr == null) return null;

        if (adr.compareTo(ADR_OVERHEATED) >= 0) {
            return MarketCondition.OVERHEATED;
        } else if (adr.compareTo(ADR_EXTREME_FEAR) <= 0) {
            return MarketCondition.EXTREME_FEAR;
        } else if (adr.compareTo(ADR_OVERSOLD) <= 0) {
            return MarketCondition.OVERSOLD;
        } else {
            return MarketCondition.NORMAL;
        }
    }

    /**
     * 시장 진단 메시지 생성
     */
    private String generateDiagnosis(MarketStatusDto kospi, MarketStatusDto kosdaq, BigDecimal combinedAdr) {
        StringBuilder sb = new StringBuilder();

        if (combinedAdr != null) {
            sb.append(String.format("종합 ADR(20일): %.1f ", combinedAdr));

            if (combinedAdr.compareTo(ADR_OVERHEATED) >= 0) {
                sb.append("- 시장이 과열 상태입니다. ");
            } else if (combinedAdr.compareTo(ADR_EXTREME_FEAR) <= 0) {
                sb.append("- 극심한 공포 구간입니다. ");
            } else if (combinedAdr.compareTo(ADR_OVERSOLD) <= 0) {
                sb.append("- 시장이 침체 구간입니다. ");
            } else {
                sb.append("- 시장이 정상 범위입니다. ");
            }
        }

        // 당일 상황 추가
        if (kospi != null && kospi.getDailyRatio() != null) {
            sb.append(String.format("| 코스피 당일 등락비: %.1f ", kospi.getDailyRatio()));
        }
        if (kosdaq != null && kosdaq.getDailyRatio() != null) {
            sb.append(String.format("| 코스닥 당일 등락비: %.1f", kosdaq.getDailyRatio()));
        }

        return sb.toString();
    }

    /**
     * Entity를 DTO로 변환
     */
    private MarketStatusDto convertToStatusDto(MarketDailyStatus entity) {
        return MarketStatusDto.builder()
                .marketType(entity.getMarketType())
                .tradeDate(entity.getTradeDate())
                .advancingCount(entity.getAdvancingCount())
                .decliningCount(entity.getDecliningCount())
                .unchangedCount(entity.getUnchangedCount())
                .upperLimitCount(entity.getUpperLimitCount())
                .lowerLimitCount(entity.getLowerLimitCount())
                .dailyRatio(entity.getDailyRatio())
                .adr20(entity.getAdr20())
                .condition(determineCondition(entity.getAdr20()))
                .indexClose(entity.getIndexClose())
                .indexChangeRate(entity.getIndexChangeRate())
                .tradingValue(entity.getTradingValue())
                .build();
    }

    /**
     * 빈 타이밍 데이터 생성
     */
    private MarketTimingDto createEmptyTiming() {
        return MarketTimingDto.builder()
                .analysisDate(LocalDate.now())
                .diagnosis("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.")
                .strategy("데이터 수집 후 분석을 이용해주세요.")
                .build();
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 시장 상태 알림 발송
     * - 시장이 과열 또는 공포 구간일 때 알림
     * - 스케줄러에서 데이터 수집 후 호출 권장
     *
     * @param onlyExtreme true면 과열/극심한공포일 때만 알림, false면 항상 알림
     * @return 알림 발송 여부
     */
    public boolean sendMarketStatusAlert(boolean onlyExtreme) {
        log.info("시장 상태 알림 발송 시작 - onlyExtreme: {}", onlyExtreme);

        MarketTimingDto timing = getCurrentMarketTiming();

        if (timing.getCombinedAdr() == null || timing.getOverallCondition() == null) {
            log.info("시장 데이터 부족으로 알림 발송 생략");
            return false;
        }

        MarketCondition condition = timing.getOverallCondition();

        // 극단적 상황만 알림하는 경우
        if (onlyExtreme) {
            if (condition != MarketCondition.OVERHEATED && condition != MarketCondition.EXTREME_FEAR) {
                log.info("시장 상태가 정상 범위이므로 알림 발송 생략 - condition: {}", condition);
                return false;
            }
        }

        telegramNotificationService.sendMarketStatusAlert(
                condition.name(),
                timing.getCombinedAdr(),
                timing.getDiagnosis()
        );

        log.info("시장 상태 알림 발송 완료 - condition: {}, ADR: {}", condition, timing.getCombinedAdr());
        return true;
    }

    /**
     * 데이터 수집 및 알림 발송 (통합)
     * - 스케줄러에서 사용하기 좋은 통합 메서드
     */
    @Transactional
    public void collectAndNotify() {
        collectMarketData();
        sendMarketStatusAlert(true);  // 극단적 상황만 알림
    }

    // ========== 기간별 데이터 수집 (Backfill) ==========

    /**
     * 특정 기간 동안의 시장 데이터 수집 (Backfill)
     * - 네이버 금융 차단 방지를 위해 요청 간 1초 딜레이 적용
     * - 주말은 자동으로 스킵
     *
     * @param startDate 시작 날짜
     * @param endDate   종료 날짜
     * @return 수집 결과 (성공 일수, 실패 일수, 스킵 일수)
     */
    @Transactional
    public java.util.Map<String, Object> collectMarketDataForPeriod(LocalDate startDate, LocalDate endDate) {
        log.info("========== 기간별 시장 데이터 수집 시작: {} ~ {} ==========", startDate, endDate);

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        LocalDate currentDate = startDate;

        while (!currentDate.isAfter(endDate)) {
            // 주말 스킵
            java.time.DayOfWeek dayOfWeek = currentDate.getDayOfWeek();
            if (dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                log.debug("주말 스킵: {}", currentDate);
                skipCount++;
                currentDate = currentDate.plusDays(1);
                continue;
            }

            try {
                // 네이버 금융 차단 방지를 위한 딜레이 (첫 요청 제외)
                if (successCount + failCount > 0) {
                    Thread.sleep(1000);  // 1초 딜레이
                }

                // 해당 날짜로 데이터 수집
                collectMarketDataForDate(currentDate);
                successCount++;
                log.info("시장 데이터 수집 완료: {} ({}/{})",
                        currentDate, successCount, java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("수집 중단됨");
                break;
            } catch (Exception e) {
                log.warn("시장 데이터 수집 실패 [{}]: {}", currentDate, e.getMessage());
                failCount++;
            }

            currentDate = currentDate.plusDays(1);
        }

        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("skipCount", skipCount);
        result.put("totalDays", java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1);

        log.info("========== 기간별 시장 데이터 수집 완료 - 성공: {}, 실패: {}, 스킵: {} ==========",
                successCount, failCount, skipCount);

        return result;
    }

    /**
     * 특정 날짜의 시장 데이터 수집
     * - 과거 날짜의 경우 네이버 금융 시세 페이지에서 수집 시도
     */
    private void collectMarketDataForDate(LocalDate targetDate) {
        log.debug("날짜별 시장 데이터 수집: {}", targetDate);

        // 오늘 날짜인 경우 기존 메서드 사용
        if (targetDate.equals(LocalDate.now())) {
            collectMarketData();
            return;
        }

        // 과거 날짜의 경우: 네이버 금융에서 해당 날짜 데이터 수집
        // 네이버 금융 상승/하락 페이지는 당일 데이터만 제공하므로,
        // 과거 데이터는 지수 정보 위주로 수집하고 상승/하락 종목 수는 0으로 설정
        collectHistoricalMarketData("KOSPI", targetDate);
        collectHistoricalMarketData("KOSDAQ", targetDate);
    }

    /**
     * 과거 날짜의 시장 데이터 수집
     * - 1차: KRX API로 상승/하락 종목 수 수집 시도
     * - 2차: 네이버 금융 일별 시세에서 지수 정보 크롤링
     */
    private void collectHistoricalMarketData(String marketType, LocalDate targetDate) {
        try {
            // 1차: KRX API로 상승/하락 종목 수 수집 시도
            int[] krxResult = collectFromKrxApi(marketType, targetDate);
            int advancingCount = krxResult[0];
            int decliningCount = krxResult[1];
            int unchangedCount = krxResult[2];

            // 2차: 네이버 금융에서 지수 정보 크롤링
            String code = "KOSPI".equals(marketType) ? "KOSPI" : "KOSDAQ";
            String url = String.format(
                    "https://finance.naver.com/sise/sise_index_day.naver?code=%s&page=1", code);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .get();

            // 테이블에서 해당 날짜 데이터 찾기
            Elements rows = doc.select("table.type_1 tr");
            BigDecimal indexClose = null;
            BigDecimal indexChangeRate = null;

            String targetDateStr = targetDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));

            for (Element row : rows) {
                Elements tds = row.select("td");
                if (tds.size() >= 6) {
                    String dateText = tds.get(0).text().trim();
                    if (dateText.equals(targetDateStr)) {
                        // 종가
                        String closeText = tds.get(1).text().replace(",", "").trim();
                        if (!closeText.isEmpty()) {
                            indexClose = new BigDecimal(closeText);
                        }

                        // 등락률 계산 (전일대비 / 종가 * 100)
                        String changeText = tds.get(2).text().replace(",", "").trim();
                        if (!changeText.isEmpty() && indexClose != null && indexClose.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal change = new BigDecimal(changeText);
                            BigDecimal prevClose = indexClose.subtract(change);
                            if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                                indexChangeRate = change.divide(prevClose, 4, RoundingMode.HALF_UP)
                                        .multiply(new BigDecimal("100"))
                                        .setScale(2, RoundingMode.HALF_UP);
                            }
                        }
                        break;
                    }
                }
            }

            // 데이터 저장
            Optional<MarketDailyStatus> existing = marketDailyStatusRepository
                    .findByMarketTypeAndTradeDate(marketType, targetDate);

            MarketDailyStatus status;
            if (existing.isPresent()) {
                status = existing.get();
            } else {
                status = new MarketDailyStatus();
                status.setMarketType(marketType);
                status.setTradeDate(targetDate);
            }

            // 지수 정보 업데이트
            if (indexClose != null) {
                status.setIndexClose(indexClose);
            }
            if (indexChangeRate != null) {
                status.setIndexChangeRate(indexChangeRate);
            }

            // KRX에서 가져온 상승/하락 종목 수 설정
            status.setAdvancingCount(advancingCount);
            status.setDecliningCount(decliningCount);
            status.setUnchangedCount(unchangedCount);
            status.setTotalCount(advancingCount + decliningCount + unchangedCount);

            marketDailyStatusRepository.save(status);
            log.info("{} {} 과거 데이터 저장 완료: 상승={}, 하락={}, 보합={}",
                    marketType, targetDate, advancingCount, decliningCount, unchangedCount);

        } catch (Exception e) {
            log.warn("{} {} 과거 데이터 수집 실패: {}", marketType, targetDate, e.getMessage());
            throw new RuntimeException(marketType + " " + targetDate + " 데이터 수집 실패: " + e.getMessage());
        }
    }

    // ========== KRX API 연동 ==========

    /**
     * KRX API로 특정 날짜의 상승/하락/보합 종목 수 수집
     * - BLD: MDCSTAT00101 (전체 시장 현황)
     *
     * @return [상승, 하락, 보합] 배열
     */
    private int[] collectFromKrxApi(String marketType, LocalDate targetDate) {
        int[] result = {0, 0, 0};  // [상승, 하락, 보합]

        try {
            String dateStr = targetDate.format(DateTimeFormatter.BASIC_ISO_DATE);
            String mktId = "KOSPI".equals(marketType) ? "STK" : "KSQ";

            // OTP 획득
            String otp = getKrxOtp(mktId, dateStr);
            if (otp == null) {
                log.debug("KRX OTP 획득 실패 - 기본값 사용");
                return result;
            }

            // 데이터 요청
            String jsonData = requestKrxData(otp);
            if (jsonData == null) {
                log.debug("KRX 데이터 요청 실패 - 기본값 사용");
                return result;
            }

            // JSON 파싱
            JsonNode root = objectMapper.readTree(jsonData);
            JsonNode outBlock = root.get("output");

            if (outBlock != null && outBlock.isArray() && outBlock.size() > 0) {
                // 첫 번째 데이터 (시장 전체)
                JsonNode item = outBlock.get(0);

                result[0] = getIntValue(item, "FLUC_TP_CD_RISE_CNT", "RISE_CNT", "ADV_CNT");  // 상승
                result[1] = getIntValue(item, "FLUC_TP_CD_FALL_CNT", "FALL_CNT", "DEC_CNT");  // 하락
                result[2] = getIntValue(item, "FLUC_TP_CD_UNCH_CNT", "UNCH_CNT", "FLAT_CNT"); // 보합

                log.debug("KRX {} {} 데이터: 상승={}, 하락={}, 보합={}",
                        marketType, targetDate, result[0], result[1], result[2]);
            }

        } catch (Exception e) {
            log.debug("KRX API 호출 실패 [{}]: {}", marketType, e.getMessage());
        }

        return result;
    }

    /**
     * KRX OTP 획득
     */
    private String getKrxOtp(String mktId, String trdDd) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", KRX_USER_AGENT);
            headers.set("Referer", "http://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201");
            headers.set("Origin", "http://data.krx.co.kr");

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", KRX_MARKET_STATUS_BLD);
            params.add("mktId", mktId);
            params.add("trdDd", trdDd);
            params.add("csvxls_isNo", "false");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_OTP_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String otp = response.getBody().trim();
                if (!otp.contains("<html>") && !otp.isEmpty()) {
                    return otp;
                }
            }
        } catch (Exception e) {
            log.debug("KRX OTP 획득 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * KRX 데이터 요청
     */
    private String requestKrxData(String otp) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", KRX_USER_AGENT);
            headers.set("Referer", "http://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201");

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", otp);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_DATA_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String body = response.getBody();
                if (!body.contains("<html>")) {
                    return body;
                }
            }
        } catch (Exception e) {
            log.debug("KRX 데이터 요청 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * JSON에서 int 값 추출 (여러 필드명 시도)
     */
    private int getIntValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            if (node.has(field)) {
                String value = node.get(field).asText().replace(",", "").trim();
                if (!value.isEmpty()) {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        // 무시
                    }
                }
            }
        }
        return 0;
    }
}
